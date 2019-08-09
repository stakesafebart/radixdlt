package com.radixdlt.tempo.store;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableSet;
import com.radixdlt.ledger.LedgerIndex;
import com.radixdlt.serialization.DsonOutput;
import com.radixdlt.serialization.SerializerConstants;
import com.radixdlt.serialization.SerializerDummy;
import com.radixdlt.serialization.SerializerId2;
import com.radixdlt.tempo.TempoAtom;
import com.radixdlt.tempo.exceptions.TempoException;
import com.radixdlt.utils.Longs;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SerializerId2("tempo.indices")
public final class TempoAtomIndices {
	// Placeholder for the serializer ID
	@JsonProperty(SerializerConstants.SERIALIZER_NAME)
	@DsonOutput(DsonOutput.Output.ALL)
	private SerializerDummy serializer = SerializerDummy.DUMMY;

	public static final byte ATOM_INDEX_PREFIX = 0;
	public static final byte SHARD_INDEX_PREFIX = 1;

	@JsonProperty("unique")
	@DsonOutput(DsonOutput.Output.ALL)
	private ImmutableSet<LedgerIndex> uniqueIndices;

	@JsonProperty("duplicate")
	@DsonOutput(DsonOutput.Output.ALL)
	private ImmutableSet<LedgerIndex> duplicateIndices;

	private TempoAtomIndices() {
		// For serializer
	}

	private TempoAtomIndices(ImmutableSet<LedgerIndex> uniqueIndices, ImmutableSet<LedgerIndex> duplicateIndices) {
		this.uniqueIndices = uniqueIndices;
		this.duplicateIndices = duplicateIndices;
	}

	Set<LedgerIndex> getUniqueIndices() {
		return this.uniqueIndices;
	}

	Set<LedgerIndex> getDuplicateIndices() {
		return this.duplicateIndices;
	}

	static TempoAtomIndices from(TempoAtom atom, Set<LedgerIndex> uniqueIndices, Set<LedgerIndex> duplicateIndices) {
		List<LedgerIndex> offendingIndices = Stream.concat(uniqueIndices.stream(), duplicateIndices.stream())
			.filter(index -> index.getPrefix() == ATOM_INDEX_PREFIX || index.getPrefix() == SHARD_INDEX_PREFIX)
			.collect(Collectors.toList());
		if (!offendingIndices.isEmpty()) {
			throw new TempoException(String.format(
				"Prefixes %s and %s are reserved for internal use but are used by %s",
				ATOM_INDEX_PREFIX, SHARD_INDEX_PREFIX, offendingIndices));
		}

		ImmutableSet.Builder<LedgerIndex> allUniqueIndices = ImmutableSet.builder();
		ImmutableSet.Builder<LedgerIndex> allDuplicateIndices = ImmutableSet.builder();

		// add application indices
		allUniqueIndices.addAll(uniqueIndices);
		allDuplicateIndices.addAll(duplicateIndices);

		// add internal indices
		allUniqueIndices.add(new LedgerIndex(ATOM_INDEX_PREFIX, atom.getAID().getBytes()));
		for (Long shard : atom.getShards()) {
			allDuplicateIndices.add(new LedgerIndex(SHARD_INDEX_PREFIX, Longs.toByteArray(shard)));
		}

		return new TempoAtomIndices(allUniqueIndices.build(), allDuplicateIndices.build());
	}
}
