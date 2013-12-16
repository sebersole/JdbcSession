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
package org.hibernate.resource.transaction.internal;

import org.hibernate.engine.transaction.jta.platform.spi.JtaPlatform;
import org.hibernate.resource.transaction.TransactionCoordinatorJtaBuilder;
import org.hibernate.resource.transaction.TransactionCoordinator;
import org.hibernate.resource.transaction.TransactionCoordinatorOwner;

/**
 * Concrete builder for JTA-based TransactionCoordinator instances.
 *
 * @author Steve Ebersole
 */
public class TransactionCoordinatorJtaBuilderImpl implements TransactionCoordinatorJtaBuilder {
	private JtaPlatform jtaPlatform;
	private boolean autoJoinTransactions = true;
	private boolean preferUserTransactions;
	private boolean performJtaThreadTracking = true;

	@Override
	public TransactionCoordinatorJtaBuilder setJtaPlatform(JtaPlatform jtaPlatform) {
		this.jtaPlatform = jtaPlatform;
		return this;
	}

	@Override
	public TransactionCoordinatorJtaBuilder setAutoJoinTransactions(boolean autoJoinTransactions) {
		this.autoJoinTransactions = autoJoinTransactions;
		return this;
	}

	@Override
	public TransactionCoordinatorJtaBuilder setPreferUserTransactions(boolean preferUserTransactions) {
		this.preferUserTransactions = preferUserTransactions;
		return this;
	}

	@Override
	public TransactionCoordinatorJtaBuilder setPerformJtaThreadTracking(boolean performJtaThreadTracking) {
		this.performJtaThreadTracking = performJtaThreadTracking;
		return this;
	}

	@Override
	public TransactionCoordinator buildTransactionCoordinator(TransactionCoordinatorOwner owner) {
		return new TransactionCoordinatorJtaImpl(
				owner,
				jtaPlatform,
				autoJoinTransactions,
				preferUserTransactions,
				performJtaThreadTracking
		);
	}
}
