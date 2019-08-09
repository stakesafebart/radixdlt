package com.radixdlt.tempo.store;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.radixdlt.common.AID;
import com.radixdlt.common.Pair;
import com.radixdlt.ledger.LedgerCursor;
import com.radixdlt.ledger.LedgerCursor.Type;
import com.radixdlt.ledger.LedgerIndex;
import com.radixdlt.ledger.LedgerSearchMode;
import com.radixdlt.ledger.exceptions.LedgerKeyConstraintException;
import com.radixdlt.serialization.DsonOutput;
import com.radixdlt.serialization.DsonOutput.Output;
import com.radixdlt.serialization.Serialization;
import com.radixdlt.serialization.SerializationException;
import com.radixdlt.tempo.AtomStore;
import com.radixdlt.tempo.AtomStoreView;
import com.radixdlt.tempo.TempoAtom;
import com.radixdlt.tempo.exceptions.TempoException;
import com.radixdlt.tempo.sync.IterativeCursor;
import com.radixdlt.utils.Longs;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseNotFoundException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.SecondaryConfig;
import com.sleepycat.je.SecondaryCursor;
import com.sleepycat.je.SecondaryDatabase;
import com.sleepycat.je.SecondaryMultiKeyCreator;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.UniqueConstraintException;
import org.bouncycastle.util.Arrays;
import org.radix.database.DatabaseEnvironment;
import org.radix.database.exceptions.DatabaseException;
import org.radix.logging.Logger;
import org.radix.logging.Logging;
import org.radix.shards.ShardRange;
import org.radix.shards.ShardSpace;
import org.radix.time.TemporalVertex;
import org.radix.universe.system.LocalSystem;
import org.radix.utils.SystemProfiler;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.radixdlt.tempo.store.TempoAtomIndices.ATOM_INDEX_PREFIX;
import static com.radixdlt.tempo.store.TempoAtomIndices.SHARD_INDEX_PREFIX;

public class TempoAtomStore implements AtomStore {
	private static final String ATOM_INDICES_DB_NAME = "tempo2.atom_indices";
	private static final String DUPLICATE_INDICES_DB_NAME = "tempo2.duplicated_indices";
	private static final String UNIQUE_INDICES_DB_NAME = "tempo2.unique_indices";
	private static final String ATOMS_DB_NAME = "tempo2.atoms";
	private static final Logger logger = Logging.getLogger("Store");

	private final Serialization serialization;
	private final SystemProfiler profiler;
	private final LocalSystem localSystem;
	private final Supplier<DatabaseEnvironment> dbEnv;
	private final AtomStoreViewAdapter view;
	private final Map<Long, TempoAtomIndices> currentIndices = new ConcurrentHashMap<>();

	private Database atoms;
	private SecondaryDatabase uniqueIndices;
	private SecondaryDatabase duplicatedIndices;
	private Database atomIndices;

	public TempoAtomStore(Serialization serialization, SystemProfiler profiler, LocalSystem localSystem, Supplier<DatabaseEnvironment> dbEnv) {
		this.serialization = Objects.requireNonNull(serialization, "serialization is required");
		this.profiler = Objects.requireNonNull(profiler, "profiler is required");
		this.localSystem = Objects.requireNonNull(localSystem, "localSystem is required");
		this.dbEnv = Objects.requireNonNull(dbEnv, "dbEnv is required");

		this.view = new AtomStoreViewAdapter(this);
	}

	@Override
	public void open() {
		DatabaseConfig primaryConfig = new DatabaseConfig();
		primaryConfig.setAllowCreate(true);
		primaryConfig.setTransactional(true);
		primaryConfig.setKeyPrefixing(true);
		primaryConfig.setBtreeComparator(TempoAtomStore.AtomStorePackedPrimaryKeyComparator.class);

		SecondaryConfig uniqueIndicesConfig = new SecondaryConfig();
		uniqueIndicesConfig.setAllowCreate(true);
		uniqueIndicesConfig.setTransactional(true);
		uniqueIndicesConfig.setMultiKeyCreator(AtomSecondaryCreator.from(this.currentIndices, TempoAtomIndices::getUniqueIndices));

		SecondaryConfig duplicateIndicesConfig = new SecondaryConfig();
		duplicateIndicesConfig.setAllowCreate(true);
		duplicateIndicesConfig.setTransactional(true);
		duplicateIndicesConfig.setSortedDuplicates(true);
		duplicateIndicesConfig.setMultiKeyCreator(AtomSecondaryCreator.from(this.currentIndices, TempoAtomIndices::getDuplicateIndices));

		DatabaseConfig indicesConfig = new DatabaseConfig();
		indicesConfig.setAllowCreate(true);
		indicesConfig.setTransactional(true);
		indicesConfig.setBtreeComparator(TempoAtomStore.AtomStorePackedPrimaryKeyComparator.class);

		try {
			Environment dbEnv = this.dbEnv.get().getEnvironment();
			this.atoms = dbEnv.openDatabase(null, ATOMS_DB_NAME, primaryConfig);
			this.uniqueIndices = dbEnv.openSecondaryDatabase(null, UNIQUE_INDICES_DB_NAME, this.atoms, uniqueIndicesConfig);
			this.duplicatedIndices = dbEnv.openSecondaryDatabase(null, DUPLICATE_INDICES_DB_NAME, this.atoms, duplicateIndicesConfig);
			this.atomIndices = dbEnv.openDatabase(null, ATOM_INDICES_DB_NAME, primaryConfig);
		} catch (Exception e) {
			throw new TempoException("Error while opening databases", e);
		}

		if (System.getProperty("db.check_integrity", "1").equals("1")) {
			// TODO implement integrity check
		}
	}

	@Override
	public void reset() {
		Transaction transaction = null;
		try {
			dbEnv.get().lock();

			Environment env = this.dbEnv.get().getEnvironment();
			transaction = env.beginTransaction(null, new TransactionConfig().setReadUncommitted(true));
			env.truncateDatabase(transaction, ATOMS_DB_NAME, false);
			env.truncateDatabase(transaction, UNIQUE_INDICES_DB_NAME, false);
			env.truncateDatabase(transaction, DUPLICATE_INDICES_DB_NAME, false);
			env.truncateDatabase(transaction, ATOM_INDICES_DB_NAME, false);
			transaction.commit();
		} catch (DatabaseNotFoundException e) {
			if (transaction != null) {
				transaction.abort();
			}

			logger.warn("Error while resetting database, database not found", e);
		} catch (Exception e) {
			if (transaction != null) {
				transaction.abort();
			}

			throw new TempoException("Error while resetting databases", e);
		} finally {
			dbEnv.get().unlock();
		}
	}

	@Override
	public void close() {
		if (this.uniqueIndices != null) {
			this.uniqueIndices.close();
		}
		if (this.duplicatedIndices != null) {
			this.duplicatedIndices.close();
		}
		if (this.atoms != null) {
			this.atoms.close();
		}
		if (this.atomIndices != null) {
			this.atomIndices.close();
		}
	}

	@Override
	public AtomStoreView asReadOnlyView() {
		return this.view;
	}

	private void fail(String message) {
		logger.error(message);
		throw new TempoException(message);
	}

	private void fail(String message, Exception cause) {
		logger.error(message, cause);
		throw new TempoException(message, cause);
	}

	public boolean contains(long clock) {
		long start = profiler.begin();
		try {
			DatabaseEntry key = new DatabaseEntry(Longs.toByteArray(clock));
			if (this.atoms.get(null, key, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
				return true;
			}
		} finally {
			profiler.incrementFrom("ATOM_STORE:CONTAINS:CLOCK", start);
		}

		return false;
	}

	@Override
	public boolean contains(AID aid) {
		long start = profiler.begin();
		try {
			DatabaseEntry key = new DatabaseEntry(LedgerIndex.from(ATOM_INDEX_PREFIX, aid.getBytes()));
			return OperationStatus.SUCCESS == this.uniqueIndices.get(null, key, null, LockMode.DEFAULT);
		} finally {
			profiler.incrementFrom("ATOM_STORE:CONTAINS:CLOCK", start);
		}
	}

	public Optional<TempoAtom> get(long clock) {
		long start = profiler.begin();
		try {
			DatabaseEntry key = new DatabaseEntry(Longs.toByteArray(clock));
			DatabaseEntry value = new DatabaseEntry();

			if (this.atoms.get(null, key, value, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
				return Optional.of(serialization.fromDson(value.getData(), TempoAtom.class));
			}
		} catch (SerializationException e) {
			fail("Get of TempoAtom with clock " + clock + " failed", e);
		} finally {
			profiler.incrementFrom("ATOM_STORE:GET:CLOCK", start);
		}

		return Optional.empty();
	}

	@Override
	public Optional<TempoAtom> get(AID aid) {
		long start = profiler.begin();
		try {
			DatabaseEntry key = new DatabaseEntry(LedgerIndex.from(ATOM_INDEX_PREFIX, aid.getBytes()));
			DatabaseEntry value = new DatabaseEntry();

			if (this.uniqueIndices.get(null, key, value, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
				return Optional.of(serialization.fromDson(value.getData(), TempoAtom.class));
			}
		} catch (Exception e) {
			fail("Get of atom '" + aid + "' failed", e);
		} finally {
			profiler.incrementFrom("ATOM_STORE:GET:AID", start);
		}

		return Optional.empty();
	}

	@Override
	public synchronized boolean store(TempoAtom atom, Set<LedgerIndex> uniqueIndices, Set<LedgerIndex> duplicateIndices) {
		long start = profiler.begin();
		Transaction transaction = dbEnv.get().getEnvironment().beginTransaction(null, null);
		try {
			if (doStore(atom, uniqueIndices, duplicateIndices, transaction)) {
				transaction.commit();
				return true;
			} else {
				transaction.abort();
			}
		} catch (LedgerKeyConstraintException e) {
			transaction.abort();
			throw e;
		} catch (Exception e) {
			transaction.abort();
			fail("Store of atom '" + atom.getAID() + "' failed", e);
		} finally {
			profiler.incrementFrom("ATOM_STORE:STORE", start);
		}
		return false;
	}

	@Override
	public boolean delete(AID aid) {
		long start = profiler.begin();
		Transaction transaction = dbEnv.get().getEnvironment().beginTransaction(null, null);
		try {
			if (doDelete(aid, transaction)) {
				transaction.commit();
				return true;
			} else {
				transaction.abort();
			}
		} catch (Exception e) {
			transaction.abort();
			fail("Delete of atom '" + aid + "' failed", e);
		} finally {
			profiler.incrementFrom("ATOM_STORE:DELETE_ATOM", start);
		}

		return false;
	}

	@Override
	public boolean replace(Set<AID> aids, TempoAtom atom, Set<LedgerIndex> uniqueIndices, Set<LedgerIndex> duplicateIndices) {
		long start = profiler.begin();
		Transaction transaction = dbEnv.get().getEnvironment().beginTransaction(null, null);
		try {
			for (AID aid : aids) {
				if (!doDelete(aid, transaction)) {
					transaction.abort();
					return false;
				}
			}
			if (doStore(atom, uniqueIndices, duplicateIndices, transaction)) {
				transaction.commit();
				return true;
			} else {
				transaction.abort();
			}
		} catch (LedgerKeyConstraintException e) {
			transaction.abort();
			throw e;
		} catch (Exception e) {
			transaction.abort();
			fail("Store of atom '" + atom.getAID() + "' failed", e);
		} finally {
			profiler.incrementFrom("ATOM_STORE:REPLACE", start);
		}
		return false;
	}

	private boolean doStore(TempoAtom atom, Set<LedgerIndex> uniqueIndices, Set<LedgerIndex> duplicateIndices, Transaction transaction) throws SerializationException {
		TemporalVertex localTemporalVertex = atom.getTemporalProof().getVertexByNID(localSystem.getNID());
		if (localTemporalVertex == null) {
			fail("Cannot store atom '" + atom.getAID() + "' without local temporal vertex");
		}
		try {
			TempoAtomIndices indices = TempoAtomIndices.from(atom, uniqueIndices, duplicateIndices);
			DatabaseEntry pKey = new DatabaseEntry(Arrays.concatenate(Longs.toByteArray(localTemporalVertex.getClock()), atom.getAID().getBytes()));
			DatabaseEntry pData = new DatabaseEntry(serialization.toDson(atom, Output.PERSIST));
			DatabaseEntry indicesData = new DatabaseEntry(serialization.toDson(indices, Output.PERSIST));

			// put indices in temporary map for key creator to pick up
			this.currentIndices.put(localTemporalVertex.getClock(), indices);
			OperationStatus status = this.atoms.putNoOverwrite(transaction, pKey, pData);
			if (status != OperationStatus.SUCCESS) {
				return false;
			}
			status = this.atomIndices.putNoOverwrite(transaction, pKey, indicesData);
			if (status != OperationStatus.SUCCESS) {
				fail("Internal error, atom indices write failed with status " + status);
			}
		} catch (UniqueConstraintException e) {
			logger.error("Unique indices violated key constraint", e);
			throw new LedgerKeyConstraintException(ImmutableSet.copyOf(uniqueIndices), e);
		} finally {
			this.currentIndices.remove(localTemporalVertex.getClock());
		}
		return true;
	}

	private boolean doDelete(AID aid, Transaction transaction) throws SerializationException {
		DatabaseEntry pKey = new DatabaseEntry();
		DatabaseEntry key = new DatabaseEntry(LedgerIndex.from(ATOM_INDEX_PREFIX, aid.getBytes()));
		DatabaseEntry indicesData = new DatabaseEntry();

		OperationStatus status = this.uniqueIndices.get(transaction, key, pKey, null, LockMode.RMW);
		if (status != OperationStatus.SUCCESS) {
			fail("Failed to get primary key for aid " + aid);
		}
		status = atomIndices.get(transaction, pKey, indicesData, LockMode.DEFAULT);
		if (status != OperationStatus.SUCCESS) {
			fail("Getting indices of atom '" + aid + "' failed with status " + status);
		}

		TempoAtomIndices indices = serialization.fromDson(indicesData.getData(), TempoAtomIndices.class);
		long logicalClock = Longs.fromByteArray(pKey.getData());
		try {
			currentIndices.put(logicalClock, indices);
			return atoms.delete(transaction, pKey) == OperationStatus.SUCCESS;
		} finally {
			currentIndices.remove(logicalClock);
		}
	}

	// TODO we'll need some way to collect the AID -> shard information from the AtomStore
	// leaving in place for now, stubbed, will review later when implementing ShardChecksumSync
	public Set<AID> getByShardChunkAndRange(int chunk, ShardRange range) throws DatabaseException {
		long start = profiler.begin();

		try {
			long from = ShardSpace.fromChunk(chunk, ShardSpace.SHARD_CHUNK_HALF_RANGE) + range.getLow();
			long to = from + range.getSpan();

			return this.getByShardRange(from, to);
		} catch (Exception ex) {
			throw new DatabaseException(ex);
		} finally {
			profiler.incrementFrom("ATOM_STORE:GET_BY_SHARD_CHUNK_AND_RANGE", start);
		}
	}

	private Set<AID> getByShardRange(long from, long to) throws DatabaseException {
		long start = profiler.begin();

		SecondaryCursor cursor = null;
		Set<AID> atomIds = new HashSet<>();

		try {
			cursor = this.duplicatedIndices.openCursor(null, null);

			DatabaseEntry key = new DatabaseEntry(LedgerIndex.from(SHARD_INDEX_PREFIX, Longs.toByteArray(from)));
			DatabaseEntry value = new DatabaseEntry();

			// FIXME this is slow as shit, speed it up, maybe pack clock+HID for primary and use that
			OperationStatus status = cursor.getSearchKeyRange(key, value, LockMode.DEFAULT);
			while (status == OperationStatus.SUCCESS) {
				long shard = Longs.fromByteArray(key.getData(), 1);
				if (shard < from || shard > to) {
					break;
				}

				TempoAtom atom = serialization.fromDson(value.getData(), TempoAtom.class);
				atomIds.add(atom.getAID());

				status = cursor.getNextDup(key, value, LockMode.DEFAULT);
				if (status == OperationStatus.NOTFOUND) {
					status = cursor.getNext(key, value, LockMode.DEFAULT);
				}
			}

			return atomIds;
		} catch (Exception ex) {
			throw new DatabaseException(ex);
		} finally {
			if (cursor != null) {
				cursor.close();
			}

			profiler.incrementFrom("ATOM_STORE:GET_BY_SHARD_RANGE", start);
		}
	}

	public Set<AID> getByShard(long shard) throws DatabaseException {
		long start = profiler.begin();

		SecondaryCursor cursor = null;
		Set<AID> atomIds = new HashSet<>();

		try {
			cursor = this.duplicatedIndices.openCursor(null, null);

			DatabaseEntry key = new DatabaseEntry(LedgerIndex.from(SHARD_INDEX_PREFIX, Longs.toByteArray(shard)));
			DatabaseEntry value = new DatabaseEntry();

			// FIXME this is slow as shit, speed it up, maybe pack clock+HID for primary and use that
			OperationStatus status = cursor.getSearchKey(key, value, LockMode.DEFAULT);
			while (status == OperationStatus.SUCCESS) {
				TempoAtom atom = serialization.fromDson(value.getData(), TempoAtom.class);
				atomIds.add(atom.getAID());
				status = cursor.getNextDup(key, value, LockMode.DEFAULT);
			}

			return atomIds;
		} catch (Exception ex) {
			throw new DatabaseException(ex);
		} finally {
			if (cursor != null) {
				cursor.close();
			}

			profiler.incrementFrom("ATOM_STORE:GET_BY_SHARD", start);
		}
	}

	// FIXME awful performance
	@Override
	public Pair<ImmutableList<AID>, IterativeCursor> getNext(IterativeCursor iterativeCursor, int limit, ShardSpace shardSpace) {
		List<AID> aids = Lists.newArrayList();
		long start = profiler.begin();
		long position = iterativeCursor.getLCPosition();

		try (Cursor cursor = this.atoms.openCursor(null, null)) {
			// TODO remove position + 1 to someplace else
			DatabaseEntry search = new DatabaseEntry(Longs.toByteArray(position + 1));
			DatabaseEntry value = new DatabaseEntry();
			OperationStatus status = cursor.getSearchKeyRange(search, value, LockMode.DEFAULT);
			while (status == OperationStatus.SUCCESS) {
				TempoAtom atom = serialization.fromDson(value.getData(), TempoAtom.class);
				position = Longs.fromByteArray(search.getData());
				if (shardSpace.intersects(atom.getShards())) {
					aids.add(atom.getAID());
					// abort as we've exceeded the limit
					if (aids.size() >= limit) {
						break;
					}
				}

				status = cursor.getNext(search, value, LockMode.DEFAULT);
			}
		} catch (SerializationException e) {
			throw new TempoException("Error while querying from database", e);
		} finally {
			profiler.incrementFrom("ATOM_STORE:DISCOVER:SYNC", start);
		}

		IterativeCursor nextCursor = null;
		if (position != iterativeCursor.getLCPosition()) {
			nextCursor = new IterativeCursor(position, null);
		}

		return Pair.of(ImmutableList.copyOf(aids), new IterativeCursor(iterativeCursor.getLCPosition(), nextCursor));
	}

	@Override
	public LedgerCursor search(Type type, LedgerIndex index, LedgerSearchMode mode) {
		Objects.requireNonNull(type, "type is required");
		Objects.requireNonNull(index, "index is required");
		Objects.requireNonNull(mode, "mode is required");

		SecondaryCursor databaseCursor;

		if (type == Type.UNIQUE) {
			databaseCursor = this.uniqueIndices.openCursor(null, null);
		} else if (type == Type.DUPLICATE) {
			databaseCursor = this.duplicatedIndices.openCursor(null, null);
		} else {
			throw new IllegalStateException("Type " + type + " not supported");
		}

		try {
			DatabaseEntry pKey = new DatabaseEntry();
			DatabaseEntry key = new DatabaseEntry(index.getKey());

			if (mode == LedgerSearchMode.EXACT) {
				if (databaseCursor.getSearchKey(key, pKey, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
					return new TempoCursor(this, type, pKey.getData(), key.getData());
				}
			} else if (mode == LedgerSearchMode.RANGE) {
				if (databaseCursor.getSearchKeyRange(key, pKey, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
					return new TempoCursor(this, type, pKey.getData(), key.getData());
				}
			}

			return null;
		} finally {
			databaseCursor.close();
		}
	}


	TempoCursor getNext(TempoCursor cursor) throws DatabaseException {
		try (SecondaryCursor databaseCursor = toSecondaryCursor(cursor)) {
			DatabaseEntry pKey = new DatabaseEntry(cursor.getPrimary());
			DatabaseEntry key = new DatabaseEntry(cursor.getIndex());
			if (databaseCursor.getSearchBothRange(key, pKey, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
				if (databaseCursor.getNextDup(key, pKey, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
					return new TempoCursor(this, cursor.getType(), pKey.getData(), key.getData());
				}
			}

			return null;
		} catch (Exception ex) {
			throw new DatabaseException(ex);
		}
	}

	TempoCursor getPrev(TempoCursor cursor) throws DatabaseException {
		try (SecondaryCursor databaseCursor = toSecondaryCursor(cursor)) {
			DatabaseEntry pKey = new DatabaseEntry(cursor.getPrimary());
			DatabaseEntry key = new DatabaseEntry(cursor.getIndex());
			if (databaseCursor.getSearchBothRange(key, pKey, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
				if (databaseCursor.getPrevDup(pKey, key, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
					return new TempoCursor(this, cursor.getType(), pKey.getData(), key.getData());
				}
			}

			return null;
		} catch (Exception ex) {
			throw new DatabaseException(ex);
		}
	}

	TempoCursor getFirst(TempoCursor cursor) throws DatabaseException {
		try (SecondaryCursor databaseCursor = toSecondaryCursor(cursor)) {
			DatabaseEntry pKey = new DatabaseEntry(cursor.getPrimary());
			DatabaseEntry key = new DatabaseEntry(cursor.getIndex());
			if (databaseCursor.getSearchBothRange(key, pKey, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
				if (databaseCursor.getPrevNoDup(key, pKey, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
					if (databaseCursor.getNext(key, pKey, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
						return new TempoCursor(this, cursor.getType(), pKey.getData(), key.getData());
					}
				} else if (databaseCursor.getFirst(key, pKey, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
					return new TempoCursor(this, cursor.getType(), pKey.getData(), key.getData());
				}
			}

			return null;
		} catch (Exception ex) {
			throw new DatabaseException(ex);
		}
	}

	TempoCursor getLast(TempoCursor cursor) throws DatabaseException {
		try (SecondaryCursor databaseCursor = toSecondaryCursor(cursor)) {
			DatabaseEntry pKey = new DatabaseEntry(cursor.getPrimary());
			DatabaseEntry key = new DatabaseEntry(cursor.getIndex());

			if (databaseCursor.getSearchBothRange(key, pKey, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
				if (databaseCursor.getNextNoDup(key, pKey, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
					if (databaseCursor.getPrev(key, pKey, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
						return new TempoCursor(this, cursor.getType(), pKey.getData(), key.getData());
					}
				} else if (databaseCursor.getLast(key, pKey, null, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
					return new TempoCursor(this, cursor.getType(), pKey.getData(), key.getData());
				}
			}

			return null;
		} catch (Exception ex) {
			throw new DatabaseException(ex);
		}
	}

	private SecondaryCursor toSecondaryCursor(TempoCursor cursor) {
		Objects.requireNonNull(cursor, "cursor is required");
		SecondaryCursor databaseCursor;
		if (cursor.getType().equals(Type.UNIQUE)) {
			databaseCursor = this.uniqueIndices.openCursor(null, null);
		} else if (cursor.getType().equals(Type.DUPLICATE)) {
			databaseCursor = this.duplicatedIndices.openCursor(null, null);
		} else {
			throw new IllegalStateException("Cursor type " + cursor.getType() + " not supported");
		}
		return databaseCursor;
	}

	public static class AtomStorePackedPrimaryKeyComparator implements Comparator<byte[]> {
		@Override
		public int compare(byte[] primary1, byte[] primary2) {
			for (int b = 0; b < Long.BYTES; b++) {
				if (primary1[b] < primary2[b]) {
					return -1;
				} else if (primary1[b] > primary2[b]) {
					return 1;
				}
			}

			return 0;
		}
	}

	private static class AtomSecondaryCreator implements SecondaryMultiKeyCreator {
		private final Function<DatabaseEntry, Set<LedgerIndex>> indexer;

		private AtomSecondaryCreator(Function<DatabaseEntry, Set<LedgerIndex>> indexer) {
			this.indexer = Objects.requireNonNull(indexer, "indexer is required");
		}

		@Override
		public void createSecondaryKeys(SecondaryDatabase database, DatabaseEntry key, DatabaseEntry value, Set<DatabaseEntry> secondaries) {
			// key should be primary key where first 8 bytes is the long clock
			Set<LedgerIndex> indices = indexer.apply(key);
			indices.forEach(index -> secondaries.add(new DatabaseEntry(index.getKey())));
		}

		private static AtomSecondaryCreator from(Map<Long, TempoAtomIndices> atomIndices, Function<TempoAtomIndices, Set<LedgerIndex>> indexer) {
			return new AtomSecondaryCreator(
				key -> {
					TempoAtomIndices tempoAtomIndices = atomIndices.get(Longs.fromByteArray(key.getData()));
					if (tempoAtomIndices == null) {
						throw new IllegalStateException("Indices for Atom@" + Longs.fromByteArray(key.getData()) + " not available");
					}
					return indexer.apply(tempoAtomIndices);
				}
			);
		}
	}
}
