package org.hibernate.test.resource.store.jdbc;

import java.sql.Connection;

import org.hibernate.resource.store.jdbc.JdbcSession;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author Steve Ebersole
 */
public abstract class AbstractBasicJdbcUsageTest {
	protected abstract Connection getConnection();
	protected abstract JdbcSession getJdbcSession();

	@Test
	public void testSimpleTransactionNoWork() throws Exception {
		assertTrue( getConnection().getAutoCommit() );
		getJdbcSession().getTransactionCoordinator().getPhysicalTransactionDelegate().begin();
		assertFalse( getConnection().getAutoCommit() );
		getJdbcSession().getTransactionCoordinator().getPhysicalTransactionDelegate().commit();
		assertTrue( getConnection().getAutoCommit() );
	}
}