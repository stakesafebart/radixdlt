package com.radixdlt.tempo.sync.actions;

import com.radixdlt.tempo.TempoAtom;
import com.radixdlt.tempo.sync.SyncAction;

public class ReceiveAtomAction implements SyncAction {
	private final TempoAtom atom;

	public ReceiveAtomAction(TempoAtom atom) {
		this.atom = atom;
	}

	public TempoAtom getAtom() {
		return atom;
	}
}
