/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2013, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.resource.transaction.backend.store.internal;

import javax.transaction.Status;

import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.resource.transaction.backend.store.spi.DataStoreTransaction;
import org.hibernate.resource.transaction.backend.store.spi.DataStoreTransactionAccess;
import org.hibernate.resource.transaction.internal.SynchronizationRegistryStandardImpl;
import org.hibernate.resource.transaction.SynchronizationRegistry;
import org.hibernate.resource.transaction.TransactionCoordinator;
import org.hibernate.resource.transaction.spi.TransactionCoordinatorOwner;

import static org.hibernate.internal.CoreLogging.messageLogger;

/**
 * An implementation of TransactionCoordinator based on managing a transaction through the data-store
 * specific ResourceLocalTransaction.
 *
 * @author Steve Ebersole
 *
 * @see org.hibernate.resource.transaction.backend.store.spi.DataStoreTransaction
 */
public class ResourceLocalTransactionCoordinatorImpl implements TransactionCoordinator {
	private static final CoreMessageLogger log = messageLogger( ResourceLocalTransactionCoordinatorImpl.class );

	private final DataStoreTransactionAccess dataStoreTransactionAccess;
	private final TransactionCoordinatorOwner owner;
	private final SynchronizationRegistryStandardImpl synchronizationRegistry = new SynchronizationRegistryStandardImpl();

	private TransactionDriverControlImpl physicalTransactionDelegate;

	/**
	 * Construct a ResourceLocalTransactionCoordinatorImpl instance.  package-protected to ensure access goes through
	 * builder.
	 *
	 * @param owner The owner
	 */
	ResourceLocalTransactionCoordinatorImpl(
			TransactionCoordinatorOwner owner,
		DataStoreTransactionAccess dataStoreTransactionAccess) {
		this.dataStoreTransactionAccess = dataStoreTransactionAccess;
		this.owner = owner;
	}

	@Override
	public LocalInflow getTransactionDriverControl() {
		// Again, this PhysicalTransactionDelegate will act as the bridge from the local transaction back into the
		// coordinator.  We lazily build it as we invalidate each delegate after each transaction (a delegate is
		// valid for just one transaction)
		if ( physicalTransactionDelegate == null ) {
			physicalTransactionDelegate = new TransactionDriverControlImpl( dataStoreTransactionAccess.getResourceLocalTransaction() );
		}
		return physicalTransactionDelegate;
	}

	@Override
	public void explicitJoin() {
		// nothing to do here, but log a warning
		log.callingJoinTransactionOnNonJtaEntityManager();
	}

	@Override
	public boolean isJoined() {
		log.debug( "Calling TransactionCoordinator#isJoined in resource-local mode always returns false" );
		return false;
	}

	@Override
	public void pulse() {
		// nothing to do here
	}

	@Override
	public SynchronizationRegistry getLocalSynchronizations() {
		return synchronizationRegistry;
	}


	// PhysicalTransactionDelegate ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	private void afterBeginCallback() {
		log.trace( "ResourceLocalTransactionCoordinatorImpl#afterBeginCallback" );
	}

	private void beforeCompletionCallback() {
		log.trace( "ResourceLocalTransactionCoordinatorImpl#beforeCompletionCallback" );
		owner.beforeTransactionCompletion();
		synchronizationRegistry.notifySynchronizationsBeforeTransactionCompletion();
	}

	private void afterCompletionCallback(boolean successful) {
		log.tracef( "ResourceLocalTransactionCoordinatorImpl#afterCompletionCallback(%s)", successful );
		final int statusToSend =  successful ? Status.STATUS_COMMITTED : Status.STATUS_UNKNOWN;
		synchronizationRegistry.notifySynchronizationsAfterTransactionCompletion( statusToSend );

		owner.afterTransactionCompletion( successful );

		invalidateDelegate();
	}

	private void invalidateDelegate() {
		if ( physicalTransactionDelegate == null ) {
			throw new IllegalStateException( "Physical-transaction delegate not known on attempt to invalidate" );
		}

		physicalTransactionDelegate.invalidate();
		physicalTransactionDelegate = null;
	}


	/**
	 * The delegate bridging between the local (application facing) transaction and the "physical" notion of a
	 * transaction via the JDBC Connection.
	 */
	public class TransactionDriverControlImpl implements LocalInflow {
		private final DataStoreTransaction dataStoreTransaction;
		private boolean invalid;

		public TransactionDriverControlImpl(DataStoreTransaction dataStoreTransaction) {
			super();
			this.dataStoreTransaction = dataStoreTransaction;
		}

		protected void invalidate() {
			invalid = true;
		}

		@Override
		public void begin() {
			errorIfInvalid();

			dataStoreTransaction.begin();
			ResourceLocalTransactionCoordinatorImpl.this.afterBeginCallback();
		}

		protected void errorIfInvalid() {
			if ( invalid ) {
				throw new IllegalStateException( "Physical-transaction delegate is no longer valid" );
			}
		}

		@Override
		public void commit() {
			ResourceLocalTransactionCoordinatorImpl.this.beforeCompletionCallback();
			dataStoreTransaction.commit();
			ResourceLocalTransactionCoordinatorImpl.this.afterCompletionCallback( true );
		}

		@Override
		public void rollback() {
			dataStoreTransaction.rollback();
			ResourceLocalTransactionCoordinatorImpl.this.afterCompletionCallback( false );
		}
	}
}
