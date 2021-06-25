/*
 * (C) Copyright 2020 Radix DLT Ltd
 *
 * Radix DLT Ltd licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.radixdlt.statecomputer.checkpoint;

import com.radixdlt.crypto.ECPublicKey;
import org.radix.StakeDelegation;
import org.radix.TokenIssuance;

import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.Multibinder;
import com.radixdlt.atom.TxAction;
import com.radixdlt.atommodel.system.state.ValidatorStakeData;
import com.radixdlt.ledger.VerifiedTxnsAndProof;
import com.radixdlt.utils.UInt256;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Genesis atom to be used with tests
 */
public final class MockedGenesisModule extends AbstractModule {
	@Override
	public void configure() {
		Multibinder.newSetBinder(binder(), TokenIssuance.class);
		bind(new TypeLiteral<VerifiedTxnsAndProof>() { }).annotatedWith(Genesis.class).toProvider(GenesisProvider.class).in(Scopes.SINGLETON);
		bind(new TypeLiteral<List<TxAction>>() { }).annotatedWith(Genesis.class).toInstance(List.of());
	}

	@Provides
	@Genesis
	public long timestamp() {
		return 1234L;
	}

	@Provides
	@Genesis
	public ImmutableList<StakeDelegation> stakeDelegations(
		@Genesis ImmutableList<ECPublicKey> initialValidators
	) {
		return initialValidators.stream()
			.map(v -> StakeDelegation.of(v, v, ValidatorStakeData.MINIMUM_STAKE.multiply(UInt256.from(100))))
			.collect(ImmutableList.toImmutableList());
	}

	@Provides
	@Genesis
	public ImmutableList<TokenIssuance> tokenIssuanceList(
		Set<TokenIssuance> tokenIssuanceSet,
		@Genesis ImmutableList<ECPublicKey> initialValidators
	) {
		return Stream.concat(
			tokenIssuanceSet.stream(),
			initialValidators.stream()
				.map(v -> TokenIssuance.of(v, ValidatorStakeData.MINIMUM_STAKE.multiply(UInt256.from(100))))
		)
			.sorted(Comparator.comparing(t -> t.receiver().toBase64()))
			.collect(ImmutableList.toImmutableList());
	}
}
