package org.hibernate.resource.transaction.backend.jta.internal;

import javax.transaction.Status;
import javax.transaction.TransactionManager;
import javax.transaction.UserTransaction;

import org.hibernate.TransactionException;
import org.hibernate.engine.transaction.jta.platform.spi.JtaPlatform;
import org.hibernate.resource.transaction.SynchronizationRegistry;
import org.hibernate.resource.transaction.TransactionCoordinator;
import org.hibernate.resource.transaction.spi.TransactionCoordinatorOwner;
import org.hibernate.resource.transaction.internal.SynchronizationRegistryStandardImpl;
import org.hibernate.resource.transaction.backend.jta.internal.synchronization.RegisteredSynchronization;
import org.hibernate.resource.transaction.backend.jta.internal.synchronization.SynchronizationCallbackCoordinatorNonTrackingImpl;
import org.hibernate.resource.transaction.backend.jta.internal.synchronization.SynchronizationCallbackCoordinatorTrackingImpl;
import org.hibernate.resource.transaction.backend.jta.internal.synchronization.SynchronizationCallbackTarget;
import org.hibernate.resource.transaction.backend.jta.internal.synchronization.SynchronizationCallbackCoordinator;

import org.jboss.logging.Logger;

import static org.hibernate.internal.CoreLogging.logger;

/**
 * An implementation of TransactionCoordinator based on managing a transaction through the JTA API (either TM or UT)
 *
 * @author Steve Ebersole
 */
public class JtaTransactionCoordinatorImpl implements TransactionCoordinator, SynchronizationCallbackTarget {
	private static final Logger log = logger( JtaTransactionCoordinatorImpl.class );

	private final TransactionCoordinatorOwner owner;
	private final JtaPlatform jtaPlatform;
	private final boolean autoJoinTransactions;
	private final boolean preferUserTransactions;
	private final boolean performJtaThreadTracking;

	private boolean synchronizationRegistered;
	private SynchronizationCallbackCoordinator callbackCoordinator;
	private TransactionDriverControlImpl physicalTransactionDelegate;

	private final SynchronizationRegistryStandardImpl synchronizationRegistry = new SynchronizationRegistryStandardImpl();

	/**
	 * Construct a JtaTransactionCoordinatorImpl instance.  package-protected to ensure access goes through
	 * builder.
	 *
	 * @param owner The owner
	 * @param jtaPlatform The JtaPlatform to use
	 * @param autoJoinTransactions Should JTA transactions be auto-joined?  Or should we wait for explicit join calls?
	 * @param preferUserTransactions Should we prefer using UserTransaction, as opposed to TransactionManager?
	 * @param performJtaThreadTracking Should we perform thread tracking?
	 */
	JtaTransactionCoordinatorImpl(
			TransactionCoordinatorOwner owner,
			JtaPlatform jtaPlatform,
			boolean autoJoinTransactions,
			boolean preferUserTransactions,
			boolean performJtaThreadTracking) {
		this.owner = owner;
		this.jtaPlatform = jtaPlatform;
		this.autoJoinTransactions = autoJoinTransactions;
		this.preferUserTransactions = preferUserTransactions;
		this.performJtaThreadTracking = performJtaThreadTracking;

		synchronizationRegistered = false;

		pulse();
	}

	public SynchronizationCallbackCoordinator getSynchronizationCallbackCoordinator() {
		if ( callbackCoordinator == null ) {
			callbackCoordinator = performJtaThreadTracking
					? new SynchronizationCallbackCoordinatorTrackingImpl( this )
					: new SynchronizationCallbackCoordinatorNonTrackingImpl( this );
		}
		return callbackCoordinator;
	}

	@Override
	public void pulse() {
		if ( !autoJoinTransactions ) {
			return;
		}

		if ( synchronizationRegistered ) {
			return;
		}

		// Can we resister a synchronization according to the JtaPlatform?
		if ( !jtaPlatform.canRegisterSynchronization() ) {
			log.trace( "JTA platform says we cannot currently resister synchronization; skipping" );
			return;
		}

		joinJtaTransaction();
	}

	/**
	 * Join to the JTA transaction.  Note that the underlying meaning of joining in JTA environments is to register the
	 * RegisteredSynchronization with the JTA system
	 */
	private void joinJtaTransaction() {
		if ( synchronizationRegistered ) {
			throw new TransactionException( "Hibernate RegisteredSynchronization is already registered for this coordinator" );
		}

		jtaPlatform.registerSynchronization( new RegisteredSynchronization( getSynchronizationCallbackCoordinator() ) );
		getSynchronizationCallbackCoordinator().synchronizationRegistered();
		synchronizationRegistered = true;
		log.debug( "Hibernate RegisteredSynchronization successfully registered with JTA platform" );
	}

	@Override
	public void explicitJoin() {
		if ( synchronizationRegistered ) {
			log.debug( "JTA transaction was already joined (RegisteredSynchronization already registered)" );
			return;
		}

		joinJtaTransaction();
	}

	@Override
	public boolean isJoined() {
		return synchronizationRegistered;
	}

	/**
	 * Is the RegisteredSynchronization used by Hibernate for unified JTA Synchronization callbacks registered for this
	 * coordinator?
	 *
	 * @return {@code true} indicates that a RegisteredSynchronization is currently registered for this coordinator;
	 * {@code false} indicates it is not (yet) registered.
	 */
	public boolean isSynchronizationRegistered() {
		return synchronizationRegistered;
	}

	@Override
	public LocalInflow getTransactionDriverControl() {
		if ( physicalTransactionDelegate == null ) {
			physicalTransactionDelegate = makePhysicalTransactionDelegate();
		}
		return physicalTransactionDelegate;
	}

	private TransactionDriverControlImpl makePhysicalTransactionDelegate() {
		JtaTransactionAdapter adapter;

		if ( preferUserTransactions ) {
			adapter = makeUserTransactionAdapter();

			if ( adapter == null ) {
				log.debug( "Unable to access UserTransaction, attempting to use TransactionManager instead" );
				adapter = makeTransactionManagerAdapter();
			}
		}
		else {
			adapter = makeTransactionManagerAdapter();

			if ( adapter == null ) {
				log.debug( "Unable to access TransactionManager, attempting to use UserTransaction instead" );
				adapter = makeUserTransactionAdapter();
			}
		}

		if ( adapter == null ) {
			throw new JtaPlatformInaccessibleException(
					"Unable to access TransactionManager or UserTransaction to make physical transaction delegate"
			);
		}

		return new TransactionDriverControlImpl( adapter );
	}

	private JtaTransactionAdapter makeUserTransactionAdapter() {
		try {
			final UserTransaction userTransaction = jtaPlatform.retrieveUserTransaction();
			if ( userTransaction == null ) {
				log.debug( "JtaPlatform#retrieveUserTransaction returned null" );
			}
			else {
				return new JtaTransactionAdapterUserTransactionImpl( userTransaction );
			}
		}
		catch (Exception ignore) {
			log.debugf( "JtaPlatform#retrieveUserTransaction threw an exception [%s]", ignore.getMessage() );
		}

		return null;
	}

	private JtaTransactionAdapter makeTransactionManagerAdapter() {
		try {
			final TransactionManager transactionManager = jtaPlatform.retrieveTransactionManager();
			if ( transactionManager == null ) {
				log.debug( "JtaPlatform#retrieveTransactionManager returned null" );
			}
			else {
				return new JtaTransactionAdapterTransactionManagerImpl( transactionManager );
			}
		}
		catch (Exception ignore) {
			log.debugf( "JtaPlatform#retrieveTransactionManager threw an exception [%s]", ignore.getMessage() );
		}

		return null;
	}

	@Override
	public SynchronizationRegistry getLocalSynchronizations() {
		return synchronizationRegistry;
	}


	// SynchronizationCallbackTarget ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public boolean isActive() {
		return owner.isActive();
	}

	@Override
	public void beforeCompletion() {
		owner.beforeTransactionCompletion();
		synchronizationRegistry.notifySynchronizationsBeforeTransactionCompletion();
	}

	@Override
	public void afterCompletion(boolean successful) {
		final int statusToSend =  successful ? Status.STATUS_COMMITTED : Status.STATUS_UNKNOWN;
		synchronizationRegistry.notifySynchronizationsAfterTransactionCompletion( statusToSend );

		owner.afterTransactionCompletion( successful );

		if ( physicalTransactionDelegate != null ) {
			physicalTransactionDelegate.invalidate();
		}
		physicalTransactionDelegate = null;
		synchronizationRegistered = false;
	}


	/**
	 * Implementation of the LocalInflow for this TransactionCoordinator.  Allows the
	 * local transaction ({@link org.hibernate.Transaction} to callback into this
	 * TransactionCoordinator for the purpose of driving the underlying JTA transaction.
	 */
	public class TransactionDriverControlImpl implements LocalInflow {
		private final JtaTransactionAdapter jtaTransactionAdapter;
		private boolean invalid;

		public TransactionDriverControlImpl(JtaTransactionAdapter jtaTransactionAdapter) {
			this.jtaTransactionAdapter = jtaTransactionAdapter;
		}

		protected void invalidate() {
			invalid = true;
		}

		@Override
		public void begin() {
			errorIfInvalid();

			jtaTransactionAdapter.begin();
			JtaTransactionCoordinatorImpl.this.joinJtaTransaction();
		}

		protected void errorIfInvalid() {
			if ( invalid ) {
				throw new IllegalStateException( "Physical-transaction delegate is no longer valid" );
			}
		}

		@Override
		public void commit() {
			errorIfInvalid();

			// we don't have to perform any before/after completion processing here.  We leave that for
			// the Synchronization callbacks
			jtaTransactionAdapter.commit();
		}

		@Override
		public void rollback() {
			errorIfInvalid();

			// we don't have to perform any after completion processing here.  We leave that for
			// the Synchronization callbacks
			jtaTransactionAdapter.rollback();
		}
	}

}
