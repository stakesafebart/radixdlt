/*
 * (C) Copyright 2021 Radix DLT Ltd
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
 *
 */

package com.radixdlt.statecomputer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.radixdlt.application.validators.state.ValidatorSystemMetadata;
import com.radixdlt.atom.SubstateTypeId;
import com.radixdlt.constraintmachine.REProcessedTxn;
import com.radixdlt.constraintmachine.RawSubstateBytes;
import com.radixdlt.constraintmachine.SubstateIndex;
import com.radixdlt.engine.BatchVerifier;
import com.radixdlt.engine.MetadataException;
import com.radixdlt.serialization.DeserializeException;
import com.radixdlt.statecomputer.forks.Forks;
import com.radixdlt.store.EngineStore;

import java.util.List;

public final class ForkVotesVerifier implements BatchVerifier<LedgerAndBFTProof> {

	private final BatchVerifier<LedgerAndBFTProof> baseVerifier;
	private final Forks forks;

	public ForkVotesVerifier(
		BatchVerifier<LedgerAndBFTProof> baseVerifier,
	 	Forks forks
	) {
		this.baseVerifier = baseVerifier;
		this.forks = forks;
	}

	@Override
	public LedgerAndBFTProof processMetadata(
		LedgerAndBFTProof metadata,
		EngineStore<LedgerAndBFTProof> engineStore,
		List<REProcessedTxn> txns
	) throws MetadataException {
		final var ignoredMetadata = baseVerifier.processMetadata(metadata, engineStore, txns);
		// just a sanity check, otherwise it would be silently ignored
		if (ignoredMetadata != metadata) {
			throw new IllegalStateException("Unexpected metadata modification by the baseVerifier");
		}

		// no forks checking if not end of epoch
		if (metadata.getProof().getNextValidatorSet().isEmpty()) {
			return metadata;
		}

		// add next fork hash
		final var metadataWithForks = forks.findNextForkConfig(engineStore, metadata)
			.map(nextForkConfig -> metadata.withNextForkHash(nextForkConfig.hash()))
			.orElse(metadata);

		// add next validators metadata
		return metadataWithForks
			.withValidatorsSystemMetadata(getValidatorsSystemMetadata(engineStore, metadata));
	}

	private ImmutableList<RawSubstateBytes> getValidatorsSystemMetadata(
		EngineStore<LedgerAndBFTProof> engineStore,
		LedgerAndBFTProof ledgerAndBFTProof
	) {
		final var currentFork = forks.getByHash(ledgerAndBFTProof.getCurrentForkHash()).orElseThrow();
		final var substateDeserialization = currentFork.engineRules().getParser().getSubstateDeserialization();
		final var validatorSet = ledgerAndBFTProof.getProof().getNextValidatorSet().orElseThrow();

		try (var validatorMetadataCursor = engineStore.openIndexedCursor(
			SubstateIndex.create(SubstateTypeId.VALIDATOR_SYSTEM_META_DATA.id(), ValidatorSystemMetadata.class))
		) {
			return Streams.stream(validatorMetadataCursor)
				.filter(rawSubstate -> {
					try {
						final var meta = (ValidatorSystemMetadata) substateDeserialization.deserialize(rawSubstate.getData());
						return validatorSet.containsNode(meta.getValidatorKey());
					} catch (DeserializeException ex) {
						throw new RuntimeException(ex);
					}
				})
				.collect(ImmutableList.toImmutableList());
		}
	}
}
