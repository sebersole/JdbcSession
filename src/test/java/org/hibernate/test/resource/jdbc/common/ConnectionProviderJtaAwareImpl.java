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
package org.hibernate.test.resource.jdbc.common;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;

import org.hibernate.test.resource.common.DatabaseConnectionInfo;
import org.hibernate.test.resource.transaction.common.JtaPlatformStandardTestingImpl;

import org.jboss.logging.Logger;

/**
 * A {@link javax.sql.DataSource} implementation intended for testing Hibernate/JTA interaction.  In that limited scope we
 * only ever have one single resource (the database connection) so we do not at all care about full-blown XA
 * semantics.  This class behaves accordingly.  This class also assumes usage of and access to JBossTS/Arjuna.
 *
 * @author Steve Ebersole
 * @author Jonathan Halliday
 */
public class ConnectionProviderJtaAwareImpl implements ConnectionProvider {
	private static final Logger log = Logger.getLogger( ConnectionProviderJtaAwareImpl.class );

	private static final String CONNECTION_KEY = "_database_connection";

	private List<Connection> nonEnlistedConnections = new ArrayList<Connection>();

	@SuppressWarnings("unchecked")
	private static void transferSetting(String settingName, Map source, Map target) {
		Object value = source.get( settingName );
		if ( value != null ) {
			target.put( settingName, value );
		}
	}

	@Override
	public Connection getConnection() throws SQLException {
		Transaction currentTransaction = findCurrentTransaction();

		try {
			if ( currentTransaction == null ) {
				// this block handles non enlisted connections ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
				Connection connection = DatabaseConnectionInfo.INSTANCE.makeConnection();
				nonEnlistedConnections.add( connection );
				return connection;
			}

			// this portion handles enlisted connections ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
			Connection connection = (Connection) JtaPlatformStandardTestingImpl.INSTANCE.synchronizationRegistry().getResource(
					CONNECTION_KEY
			);
			if ( connection == null ) {
				connection = DatabaseConnectionInfo.INSTANCE.makeConnection();
				JtaPlatformStandardTestingImpl.INSTANCE.synchronizationRegistry().putResource( CONNECTION_KEY, connection );

				XAResourceWrapper xaResourceWrapper = new XAResourceWrapper( this, connection );
				currentTransaction.enlistResource( xaResourceWrapper );
			}
			return connection;
		}
		catch (SQLException e) {
			throw e;
		}
		catch (Exception e) {
			throw new SQLException(e);
		}
	}

	@Override
	@SuppressWarnings("StatementWithEmptyBody")
	public void closeConnection(Connection conn) throws SQLException {
		if ( conn == null ) {
			return;
		}

		if ( nonEnlistedConnections.contains( conn ) ) {
			nonEnlistedConnections.remove( conn );
			conn.close();
		}
		// do nothing.  part of the enlistment contract here is that the XAResource wrapper
		// takes that responsibility.
	}

	@Override
	public boolean supportsAggressiveRelease() {
		return true;
	}

	protected Transaction findCurrentTransaction() {
		try {
			return JtaPlatformStandardTestingImpl.INSTANCE.transactionManager().getTransaction();
		}
		catch (SystemException e) {
			throw new IllegalStateException( "Could not locate current transaction" );
		}
	}

	@Override
	public boolean isUnwrappableAs(Class unwrapType) {
		return false;
	}

	@Override
	public <T> T unwrap(Class<T> unwrapType) {
		return (T) this;
	}

	private void delist(Connection connection) {
		// todo : verify the incoming connection is the currently enlisted one?
		JtaPlatformStandardTestingImpl.INSTANCE.synchronizationRegistry().putResource( CONNECTION_KEY, null );
		try {
			connection.close();
		}
		catch (SQLException e) {
			log.error( "Error trying to close JDBC connection from delist callbacks!!!" );
		}
	}

	public static class XAResourceWrapper implements XAResource {
		private final ConnectionProviderJtaAwareImpl pool;
		private final Connection connection;
		private int transactionTimeout;

		public XAResourceWrapper(ConnectionProviderJtaAwareImpl pool, Connection connection) {
			this.pool = pool;
			this.connection = connection;
		}

		@Override
		public int prepare(Xid xid) throws XAException {
			throw new RuntimeException("this should never be called");
		}

		@Override
		public void commit(Xid xid, boolean onePhase) throws XAException {
			if (!onePhase) {
				throw new IllegalArgumentException( "must be one phase" );
			}

			try {
				connection.commit();
			}
			catch(SQLException e) {
				throw new XAException( e.toString() );
			}
			finally {
				try {
					pool.delist( connection );
				}
				catch (Exception ignore) {
					log.debug( "Unable to delist connection on commit : " + ignore.getMessage() );
				}
			}
		}

		@Override
		public void rollback(Xid xid) throws XAException {

			try {
				connection.rollback();
			}
			catch(SQLException e) {
				throw new XAException( e.toString() );
			}
			finally {
				try {
					pool.delist( connection );
				}
				catch (Exception ignore) {
					log.debug( "Unable to delist connection on rollback : " + ignore.getMessage() );
				}
			}
		}

		@Override
		public void end(Xid xid, int i) throws XAException {
			// noop
		}

		@Override
		public void start(Xid xid, int i) throws XAException {
			// noop
		}


		@Override
		public void forget(Xid xid) throws XAException {
			// noop
		}

		@Override
		public int getTransactionTimeout() throws XAException {
			return transactionTimeout;
		}

		@Override
		public boolean setTransactionTimeout(int i) throws XAException {
			transactionTimeout = i;
			return true;
		}

		@Override
		public boolean isSameRM(XAResource xaResource) throws XAException {
			return xaResource != null && xaResource == this;
		}

		@Override
		public Xid[] recover(int i) throws XAException {
			return new Xid[0];
		}
	}
}
