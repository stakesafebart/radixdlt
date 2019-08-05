package com.radixdlt.tempo.sync.actions;

import com.radixdlt.tempo.TempoAtom;
import com.radixdlt.tempo.sync.SyncAction;
import com.radixdlt.tempo.sync.messages.DeliveryResponseMessage;
import org.radix.network.peers.Peer;

public class SendDeliveryResponseAction implements SyncAction {
	private final TempoAtom atom;
	private final Peer peer;

	public SendDeliveryResponseAction(TempoAtom atom, Peer peer) {
		this.atom = atom;
		this.peer = peer;
	}

	public TempoAtom getAtom() {
		return atom;
	}

	public Peer getPeer() {
		return peer;
	}

	public DeliveryResponseMessage toMessage() {
		return new DeliveryResponseMessage(atom);
	}

	public static SendDeliveryResponseAction from(DeliveryResponseMessage message, Peer peer) {
		return new SendDeliveryResponseAction(message.getAtom(), peer);
	}
}
