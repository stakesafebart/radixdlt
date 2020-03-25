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

package com.radixdlt.crypto;

import org.bouncycastle.util.encoders.Base64;

public class MacMismatchException extends CryptoException {
	private final byte[] expected;
	private final byte[] actual;

	public MacMismatchException(String msg, byte[] expected, byte[] actual) {
		super(msg);
		this.expected = expected;
		this.actual = actual;
	}

	public MacMismatchException(byte[] expected, byte[] actual) {
		this.expected = expected;
		this.actual = actual;
	}

	public String getExpectedBase64() {
		return Base64.toBase64String(expected);
	}

	public String getActualBase64() {
		return Base64.toBase64String(actual);
	}
}
