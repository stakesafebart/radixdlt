/*
 * (C) Copyright 2020 Radix DLT Ltd
 *
 * Radix DLT Ltd licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.radixdlt.constraintmachine;

public enum REOp {
	END(false),
	SYSCALL(false),
	UP(true),
	READ(false),
	DOWN(true),
	DOWNINDEX(true),
	SIG(false),
	MSG(false),
	HEADER(false);

	private final boolean isSubstateUpdate;

	REOp(boolean isSubstateUpdate) {
		this.isSubstateUpdate = isSubstateUpdate;
	}

	public boolean isSubstateUpdate() {
		return isSubstateUpdate;
	}

	@Override
	public String toString() {
		return this.name();
	}
}