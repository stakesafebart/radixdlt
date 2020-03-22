package com.radixdlt.consensus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.radixdlt.common.Atom;
import com.radixdlt.consensus.ChainedBFT.Event;
import com.radixdlt.consensus.liveness.PacemakerImpl;
import com.radixdlt.consensus.liveness.ProposalGenerator;
import com.radixdlt.consensus.liveness.ProposerElection;
import com.radixdlt.consensus.liveness.SingleLeader;
import com.radixdlt.consensus.safety.QuorumRequirements;
import com.radixdlt.consensus.safety.SafetyRules;
import com.radixdlt.consensus.safety.SafetyState;
import com.radixdlt.consensus.safety.WhitelistQuorum;
import com.radixdlt.crypto.ECDSASignatures;
import com.radixdlt.crypto.ECKeyPair;
import com.radixdlt.engine.RadixEngine;
import com.radixdlt.mempool.Mempool;
import com.radixdlt.network.TestEventCoordinatorNetwork;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.observers.TestObserver;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.commons.math3.util.Pair;
import org.junit.Before;
import org.junit.Test;
import org.radix.logging.Logger;
import org.radix.logging.Logging;

public class ChainedBFTTest {
	private static final Logger log = Logging.getLogger("Test");
	private final TestEventCoordinatorNetwork testEventCoordinatorNetwork = new TestEventCoordinatorNetwork();

	private Atom genesis;
	private Vertex genesisVertex;
	private QuorumCertificate genesisQC;

	@Before
	public void setup() {
		this.genesis = mock(Atom.class);
		when(this.genesis.toString()).thenReturn(Long.toHexString(0));
		this.genesisVertex = Vertex.createGenesis(genesis);
		this.genesisQC = new QuorumCertificate(
			new VertexMetadata(View.genesis(), genesisVertex.getId(), null, null),
			new ECDSASignatures()
		);
	}

	ChainedBFT createBFTInstance(
		ECKeyPair key,
		ProposerElection proposerElection,
		QuorumRequirements quorumRequirements,
		VertexStore vertexStore
	) {
		Mempool mempool = mock(Mempool.class);
		AtomicLong atomId = new AtomicLong();
		doAnswer(inv -> {
			Atom atom = mock(Atom.class);
			when(atom.toString()).thenReturn(Long.toHexString(atomId.incrementAndGet()));
			return Collections.singletonList(atom);
		}).when(mempool).getAtoms(anyInt(), anySet());

		ProposalGenerator proposalGenerator = new ProposalGenerator(vertexStore, mempool);
		SafetyRules safetyRules = new SafetyRules(key, vertexStore, SafetyState.initialState());
		PacemakerImpl pacemaker = new PacemakerImpl(quorumRequirements, Executors.newSingleThreadScheduledExecutor());
		PendingVotes pendingVotes = new PendingVotes(quorumRequirements);
		EventCoordinator eventCoordinator = new EventCoordinator(
			proposalGenerator,
			mempool,
			testEventCoordinatorNetwork,
			safetyRules,
			pacemaker,
			vertexStore,
			pendingVotes,
			proposerElection,
			key
		);

		return new ChainedBFT(
			eventCoordinator,
			testEventCoordinatorNetwork.getNetworkRx(key.getUID()),
			pacemaker
		);
	}

	@Test
	public void given_3_correct_bft_instances_with_single_leader__then_all_instances_should_get_ten_commits() throws Exception {
		final List<ECKeyPair> nodes = Arrays.asList(new ECKeyPair(), new ECKeyPair(), new ECKeyPair());
		final int commitCount = 10;

		final QuorumRequirements quorumRequirements = WhitelistQuorum.from(nodes.stream().map(ECKeyPair::getPublicKey));
		final ProposerElection proposerElection = new SingleLeader(nodes.get(0).getUID());

		final List<Pair<TestObserver<Vertex>, Observable<Event>>> bftEvents = nodes.stream()
			.map(e -> {
				RadixEngine radixEngine = mock(RadixEngine.class);
				when(radixEngine.staticCheck(any())).thenReturn(Optional.empty());
				VertexStore vertexStore = new VertexStore(genesisVertex, genesisQC, radixEngine);
				TestObserver<Vertex> testObserver = TestObserver.create();
				vertexStore.lastCommittedVertex().subscribe(testObserver);
				ChainedBFT chainedBFT = createBFTInstance(e, proposerElection, quorumRequirements, vertexStore);
				return new Pair<>(testObserver, chainedBFT.processEvents());
			})
			.collect(Collectors.toList());

		final List<TestObserver<Vertex>> committedListeners = bftEvents.stream()
			.map(Pair::getFirst)
			.collect(Collectors.toList());

		Observable.merge(bftEvents.stream().map(Pair::getSecond).collect(Collectors.toList()))
			.map(Event::toString)
			.subscribe(log::info);

		for (TestObserver<Vertex> committedListener : committedListeners) {
			committedListener.awaitCount(commitCount);
			for (int i = 0; i < commitCount; i++) {
				final int id = i;
				committedListener.assertValueAt(i, v -> v.getAtom().toString().equals(Integer.toHexString(id)));
			}
		}
	}
}
