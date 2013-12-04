package org.hibernate.test.resource.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.util.Properties;

import org.hibernate.resource.jdbc.JdbcSession;
import org.hibernate.resource.jdbc.internal.JdbcSessionImpl;
import org.hibernate.resource.jdbc.internal.LogicalConnectionProvidedImpl;
import org.hibernate.resource.jdbc.spi.JdbcSessionImplementor;
import org.hibernate.resource.jdbc.spi.TransactionCoordinatorBuilder;
import org.hibernate.resource.transaction.TransactionCoordinator;
import org.hibernate.resource.transaction.internal.TransactionCoordinatorJdbcImpl;

import org.hibernate.test.resource.jdbc.common.JdbcSessionContextStandardTestingImpl;
import org.junit.After;
import org.junit.Before;

/**
 * @author Steve Ebersole
 */
public class BasicUsageTestWithProvidedConnections extends AbstractBasicUsageTest {
	private Connection jdbcConnection;
	private JdbcSessionImpl jdbcSession;

	@Override
	protected Connection getConnection() {
		return jdbcConnection;
	}

	@Override
	protected JdbcSession getJdbcSession() {
		return jdbcSession;
	}

	@Before
	public void setUp() throws Exception {
		final Driver driver = (Driver) Class.forName( "org.h2.Driver" ).newInstance();
		final String url = "jdbc:h2:mem:db1";
		Properties connectionProperties = new Properties();
		connectionProperties.setProperty( "username", "sa" );
		connectionProperties.setProperty( "password", "" );

		jdbcConnection = driver.connect( url, connectionProperties );

		jdbcSession = new JdbcSessionImpl(
				JdbcSessionContextStandardTestingImpl.INSTANCE,
				new LogicalConnectionProvidedImpl( jdbcConnection ),
				new TransactionCoordinatorBuilder() {
					@Override
					public TransactionCoordinator buildTransactionCoordinator(JdbcSessionImplementor jdbcSession) {
						return new TransactionCoordinatorJdbcImpl( jdbcSession );
					}
				}
		);
	}

	@After
	public void tearDown() throws Exception {
		jdbcSession.close();
		jdbcConnection.close();
	}
}
