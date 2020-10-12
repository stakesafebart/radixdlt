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

package com.radixdlt.consensus.bft;

import com.radixdlt.consensus.BFTEventProcessor;
import com.radixdlt.consensus.Proposal;
import com.radixdlt.consensus.ConsensusEvent;
import com.radixdlt.consensus.SyncInfo;
import com.radixdlt.consensus.ViewTimeout;
import com.radixdlt.consensus.Vote;
import com.radixdlt.consensus.bft.BFTSyncer.SyncResult;
import com.radixdlt.consensus.bft.SyncQueues.SyncQueue;
import com.radixdlt.consensus.liveness.PacemakerState;
import com.radixdlt.consensus.liveness.ProposerElection;
import com.radixdlt.crypto.Hash;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Preprocesses consensus events and ensures that the vertexStore is synced to
 * the correct state before they get forwarded to the actual state reducer.
 *
 * This class should not be updating any part of the BFT Safety state besides
 * the VertexStore.
 *
 * A lot of the queue logic could be done more "cleanly" and functionally using
 * lambdas and Functions but the performance impact is too great.
 *
 * This class is NOT thread-safe.
 */
public final class BFTEventPreprocessor implements BFTEventProcessor {
	private static final Logger log = LogManager.getLogger();

	private final BFTNode self;
	private final BFTEventProcessor forwardTo;
	private final BFTSyncer bftSyncer;
	private final PacemakerState pacemakerState;
	private final ProposerElection proposerElection;
	private final SyncQueues queues;

	public BFTEventPreprocessor(
		BFTNode self,
		BFTEventProcessor forwardTo,
		PacemakerState pacemakerState,
		BFTSyncer bftSyncer,
		ProposerElection proposerElection,
		SyncQueues queues
	) {
		this.self = Objects.requireNonNull(self);
		this.pacemakerState = Objects.requireNonNull(pacemakerState);
		this.bftSyncer = Objects.requireNonNull(bftSyncer);
		this.proposerElection = Objects.requireNonNull(proposerElection);
		this.queues = queues;
		this.forwardTo = forwardTo;
	}

	// TODO: Cleanup
	// TODO: remove queues and treat each message independently
	private boolean clearAndExecute(SyncQueue queue, View view) {
		final ConsensusEvent event = queue.clearViewAndGetNext(view);
		if (event == null) {
			return false;
		}

		// Explicitly using switch case method here rather than functional method
		// to process these events due to much better performance
		if (event instanceof Proposal) {
			final Proposal proposal = (Proposal) event;
			return processProposalInternal(proposal);
		}

		if (event instanceof ViewTimeout) {
			final ViewTimeout viewTimeout = (ViewTimeout) event;
			return processViewTimeoutInternal(viewTimeout);
		}

		if (event instanceof Vote) {
			final Vote vote = (Vote) event;
			return processVoteInternal(vote);
		}

		throw new IllegalStateException("Unexpected consensus event: " + event);
	}

	private boolean peekAndExecute(SyncQueue queue, Hash vertexId) {
		final ConsensusEvent event = queue.peek(vertexId);
		if (event == null) {
			return false;
		}

		// Explicitly using switch case method here rather than functional method
		// to process these events due to much better performance
		if (event instanceof Proposal) {
			final Proposal proposal = (Proposal) event;
			return processProposalInternal(proposal);
		}

		if (event instanceof ViewTimeout) {
			final ViewTimeout viewTimeout = (ViewTimeout) event;
			return processViewTimeoutInternal(viewTimeout);
		}

		if (event instanceof Vote) {
			final Vote vote = (Vote) event;
			return processVoteInternal(vote);
		}

		throw new IllegalStateException("Unexpected consensus event: " + event);
	}

	@Override
	public void processBFTUpdate(BFTUpdate update) {
		Hash vertexId = update.getInsertedVertex().getId();

		log.trace("{}: LOCAL_SYNC: {}", this.self::getSimpleName, () -> vertexId);
		for (SyncQueue queue : queues.getQueues()) {
			if (peekAndExecute(queue, vertexId)) {
				queue.pop();
				while (peekAndExecute(queue, null)) {
					queue.pop();
				}
			}
		}

		forwardTo.processBFTUpdate(update);
	}

	@Override
	public void processVote(Vote vote) {
		log.trace("Vote: PreProcessing {}", vote);
		if (queues.isEmptyElseAdd(vote) && !processVoteInternal(vote)) {
			log.debug("ViewTimeout: Queuing {}, waiting for Sync", vote);
			queues.add(vote);
		}
	}

	@Override
	public void processViewTimeout(ViewTimeout newView) {
		log.trace("ViewTimeout: PreProcessing {}", newView);
		if (queues.isEmptyElseAdd(newView) && !processViewTimeoutInternal(newView)) {
			log.debug("ViewTimeout: Queuing {}, waiting for Sync", newView);
			queues.add(newView);
		}
	}

	@Override
	public void processProposal(Proposal proposal) {
		log.trace("Proposal: PreProcessing {}", proposal);
		if (queues.isEmptyElseAdd(proposal) && !processProposalInternal(proposal)) {
			log.debug("Proposal: Queuing {}, waiting for Sync", proposal);
			queues.add(proposal);
		}
	}

	@Override
	public void processLocalTimeout(View view) {
		final View curView = this.pacemakerState.getCurrentView();
		forwardTo.processLocalTimeout(view);
		final View nextView = this.pacemakerState.getCurrentView();
		if (!curView.equals(nextView)) {
			log.debug("{}: LOCAL_TIMEOUT: Clearing Queues: {}", this.self::getSimpleName, () -> queues);
			for (SyncQueue queue : queues.getQueues()) {
				if (clearAndExecute(queue, nextView.previous())) {
					queue.pop();
					while (peekAndExecute(queue, null)) {
						queue.pop();
					}
				}
			}
		}
	}

	@Override
	public void start() {
		forwardTo.start();
	}

	private boolean processViewTimeoutInternal(ViewTimeout viewTimeout) {
		log.trace("ViewTimeout: PreProcessing {}", viewTimeout);

		// Only do something if we're on the same view, and are the leader for the next view
		if (!checkForCurrentViewAndIAmNextLeader("ViewTimeout", viewTimeout.getView(), viewTimeout)) {
			return true;
		}
		return syncUp(viewTimeout.syncInfo(), viewTimeout.getAuthor(), () -> this.forwardTo.processViewTimeout(viewTimeout));
	}

	private boolean processVoteInternal(Vote vote) {
		log.trace("Vote: PreProcessing {}", vote);

		// Only do something if we're on the same view, and are the leader for the next view
		if (!checkForCurrentViewAndIAmNextLeader("Vote", vote.getView(), vote)) {
			return true;
		}
		return syncUp(vote.syncInfo(), vote.getAuthor(), () -> this.forwardTo.processVote(vote));
	}

	private boolean processProposalInternal(Proposal proposal) {
		log.trace("Proposal: PreProcessing {}", proposal);

		if (!onCurrentView("Proposal", proposal.getVertex().getView(), proposal)) {
			return true;
		}
		return syncUp(proposal.syncInfo(), proposal.getAuthor(), () -> forwardTo.processProposal(proposal));
	}

	private boolean syncUp(SyncInfo syncInfo, BFTNode author, Runnable whenSynced) {
		SyncResult syncResult = this.bftSyncer.syncToQC(syncInfo, author);
		switch (syncResult) {
			case SYNCED:
				whenSynced.run();
				return true;
			case INVALID:
				return true;
			case IN_PROGRESS:
				return false;
			default:
				throw new IllegalStateException("Unknown syncResult " + syncResult);
		}
	}

	private boolean checkForCurrentViewAndIAmNextLeader(String what, View view, Object thing) {
		if (!onCurrentView(what, view, thing)) {
			return false;
		}
		// TODO: currently we don't check view of vote relative to our pacemakerState. This opens
		// TODO: up to dos attacks on calculation of next proposer if ProposerElection is
		// TODO: an expensive operation. Need to figure out a way of mitigating this problem
		// TODO: perhaps through filter views too out of bounds
		BFTNode nextLeader = proposerElection.getProposer(view.next());
		boolean iAmTheNextLeader = Objects.equals(nextLeader, this.self);
		if (!iAmTheNextLeader) {
			log.warn("{}: Confused message for view {} (should be send to {}, I am {}): {}", what, view, nextLeader, this.self, thing);
			return false;
		}
		return true;
	}

	private boolean onCurrentView(String what, View view, Object thing) {
		final View currentView = this.pacemakerState.getCurrentView();
		if (view.compareTo(currentView) < 0) {
			log.trace("{}: Ignoring view {}, current is {}: {}", what, view, currentView, thing);
			return false;
		}
		return true;
	}
}
