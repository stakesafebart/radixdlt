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

package com.radixdlt.consensus;

import com.radixdlt.consensus.SyncedStateComputer.CommittedInstruction;
import com.radixdlt.crypto.ECPublicKey;
import java.util.List;

/**
 * A distributed computer which manages the computed state in a BFT.
 *
 * @param <T> the instruction type
 */
public interface SyncedStateComputer<T extends CommittedInstruction> {

	/**
	 * A state computer instruction which has been committed by the BFT
	 */
	interface CommittedInstruction {

		/**
		 * Retrieve the BFT data for the instruction
		 * @return the BFT data for the instruction
		 */
		VertexMetadata getVertexMetadata();
	}

	/**
	 * Syncs the computer to a target version given the target version
	 * and a list of peer targets
	 *
	 * @param targetStateVersion the target state version
	 * @param target list of targets as hint of which peer has the state
	 * @param opaque some opaque client object which will be passed in a sync
	 * message if this returns false
	 * @return a completable which completes when sync is complete
	 */
	boolean syncTo(long targetStateVersion, List<ECPublicKey> target, Object opaque);

	/**
	 * Execute a committed instruction
	 * @param instruction the instruction to execute
	 */
	void execute(T instruction);
}
