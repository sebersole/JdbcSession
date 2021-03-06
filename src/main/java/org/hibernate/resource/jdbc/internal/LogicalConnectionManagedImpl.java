package org.hibernate.resource.jdbc.internal;

import java.sql.Connection;
import java.sql.SQLException;

import org.hibernate.ConnectionReleaseMode;
import org.hibernate.ResourceClosedException;
import org.hibernate.engine.jdbc.spi.SqlExceptionHelper;
import org.hibernate.resource.jdbc.spi.JdbcConnectionAccess;
import org.hibernate.resource.jdbc.spi.JdbcObserver;
import org.hibernate.resource.jdbc.spi.JdbcSessionContext;
import org.hibernate.resource.jdbc.spi.LogicalConnectionImplementor;

import org.jboss.logging.Logger;

/**
 * Represents a LogicalConnection where we manage obtaining and releasing the Connection as needed.
 *
 * @author Steve Ebersole
 */
public class LogicalConnectionManagedImpl extends AbstractLogicalConnectionImplementor {
	private static final Logger log = Logger.getLogger( LogicalConnectionManagedImpl.class );

	private final JdbcConnectionAccess jdbcConnectionAccess;
	private final JdbcObserver observer;
	private final SqlExceptionHelper sqlExceptionHelper;
	private final ConnectionReleaseMode connectionReleaseMode;

	private Connection physicalConnection;
	private boolean closed;

	public LogicalConnectionManagedImpl(
			JdbcConnectionAccess jdbcConnectionAccess,
			JdbcSessionContext jdbcSessionContext) {
		this.jdbcConnectionAccess = jdbcConnectionAccess;
		this.observer = jdbcSessionContext.getObserver();
		this.sqlExceptionHelper = jdbcSessionContext.getSqlExceptionHelper();
		this.connectionReleaseMode = jdbcSessionContext.getConnectionReleaseMode();

		if ( jdbcSessionContext.getConnectionAcquisitionMode() == JdbcSessionContext.ConnectionAcquisitionMode.IMMEDIATELY ) {
			if ( jdbcSessionContext.getConnectionReleaseMode() != ConnectionReleaseMode.ON_CLOSE ) {
				throw new IllegalStateException(
						"Illegal combination of ConnectionAcquisitionMode#IMMEDIATELY with !ConnectionReleaseMode.ON_CLOSE"
				);
			}
			acquireConnectionIfNeeded();
		}
	}

	private Connection acquireConnectionIfNeeded() {
		if ( physicalConnection == null ) {
			// todo : is this the right place for these observer calls?
			observer.jdbcConnectionAcquisitionStart();
			try {
				physicalConnection = jdbcConnectionAccess.obtainConnection();
			}
			catch (SQLException e) {
				throw sqlExceptionHelper.convert( e, "Unable to acquire JDBC Connection" );
			}
			finally {
				observer.jdbcConnectionAcquisitionEnd();
			}
		}
		return physicalConnection;
	}

	@Override
	public boolean isOpen() {
		return !closed;
	}

	@Override
	public boolean isPhysicallyConnected() {
		return physicalConnection != null;
	}

	@Override
	public Connection getPhysicalConnection() {
		errorIfClosed();
		return acquireConnectionIfNeeded();
	}

	@Override
	public void afterStatement() {
		super.afterStatement();

		if ( connectionReleaseMode == ConnectionReleaseMode.AFTER_STATEMENT ) {
			if ( getResourceRegistry().hasRegisteredResources() ) {
				log.debug( "Skipping aggressive release of JDBC Connection after-statement due to held resources" );
			}
			else {
				log.debug( "Initiating JDBC connection release from afterStatement" );
				releaseConnection();
			}
		}
	}

	@Override
	public void afterTransaction() {
		super.afterTransaction();

		if ( connectionReleaseMode != ConnectionReleaseMode.ON_CLOSE ) {
			// NOTE : we check for !ON_CLOSE here (rather than AFTER_TRANSACTION) to also catch AFTER_STATEMENT cases
			// that were circumvented due to held resources
			log.debug( "Initiating JDBC connection release from afterTransaction" );
			releaseConnection();
		}
	}

	@Override
	public Connection manualDisconnect() {
		if ( closed ) {
			throw new ResourceClosedException( "Logical connection is closed" );
		}

		throw new IllegalStateException( "Cannot manually disconnect unless Connection was originally supplied by user" );
	}

	@Override
	public void manualReconnect(Connection suppliedConnection) {
		if ( closed ) {
			throw new ResourceClosedException( "Logical connection is closed" );
		}

		throw new IllegalStateException( "Cannot manually reconnect unless Connection was originally supplied by user" );
	}

	private void releaseConnection() {
		if ( physicalConnection == null ) {
			return;
		}

		// todo : is this the right place for these observer calls?
		observer.jdbcConnectionReleaseStart();
		try {
			if ( !physicalConnection.isClosed() ) {
				sqlExceptionHelper.logAndClearWarnings( physicalConnection );
			}
			jdbcConnectionAccess.releaseConnection( physicalConnection );
		}
		catch (SQLException e) {
			throw sqlExceptionHelper.convert( e, "Unable to release JDBC Connection" );
		}
		finally {
			observer.jdbcConnectionReleaseEnd();
		}
	}

	@Override
	public LogicalConnectionImplementor makeShareableCopy() {
		errorIfClosed();

		// todo : implement
		return null;
	}


	@Override
	public Connection close() {
		if ( closed ) {
			return null;
		}

		getResourceRegistry().releaseResources();

		log.trace( "Closing logical connection" );
		try {
			releaseConnection();
		}
		finally {
			// no matter what
			closed = true;
			log.trace( "Logical connection closed" );
		}
		return null;
	}


	// PhysicalJdbcTransaction impl ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	protected Connection getConnectionForTransactionManagement() {
		return getPhysicalConnection();
	}

	boolean initiallyAutoCommit;

	@Override
	public void begin() {
		initiallyAutoCommit = determineInitialAutoCommitMode( getConnectionForTransactionManagement() );
		super.begin();
	}

	@Override
	protected void afterCompletion() {
		afterTransaction();

		resetConnection( initiallyAutoCommit );
		initiallyAutoCommit = false;
	}
}
