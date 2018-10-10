package com.radixdlt.client.core.ledger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.radixdlt.client.core.address.RadixAddress;
import com.radixdlt.client.core.atoms.AccountReference;
import com.radixdlt.client.core.atoms.Atom;
import com.radixdlt.client.core.atoms.particles.Particle;
import com.radixdlt.client.core.atoms.particles.Spin;
import com.radixdlt.client.core.crypto.ECKeyPair;
import com.radixdlt.client.core.crypto.ECPublicKey;
import com.radixdlt.client.core.serialization.Dson;
import io.reactivex.observers.TestObserver;
import java.util.Collections;
import java.util.stream.Stream;
import org.junit.Test;

public class ValidAtomFilterTest {

	@Test
	public void testDownWithMissingUp() {
		AccountReference accountReference = mock(AccountReference.class);
		ECPublicKey ecPublicKey = mock(ECPublicKey.class);
		when(accountReference.getKey()).thenReturn(ecPublicKey);

		RadixAddress address = mock(RadixAddress.class);
		when(address.ownsKey(any(ECKeyPair.class))).thenReturn(true);
		when(address.ownsKey(any(ECPublicKey.class))).thenReturn(true);

		Particle down = mock(Particle.class);
		when(down.getAddresses()).thenReturn(Collections.singleton(ecPublicKey));

		Dson dson = mock(Dson.class);
		when(dson.toDson(down)).thenReturn(new byte[] {0});

		Particle up = mock(Particle.class);
		when(up.getAddresses()).thenReturn(Collections.singleton(ecPublicKey));
		when(dson.toDson(up)).thenReturn(new byte[] {1});

		// Build atom with consumer originating from nowhere
		Atom atom = mock(Atom.class);
		when(atom.particles(Spin.DOWN)).then(inv -> Stream.of(down));
		when(atom.particles(Spin.UP)).then(inv -> Stream.of(up));

		ValidAtomFilter validAtomFilter = new ValidAtomFilter(address, dson);

		TestObserver<Atom> observer = TestObserver.create();
		validAtomFilter.filter(atom).subscribe(observer);
		observer.assertValueCount(0);
	}

	@Test
	public void testDownBeforeUp() {
		AccountReference accountReference = mock(AccountReference.class);
		ECPublicKey ecPublicKey = mock(ECPublicKey.class);
		when(accountReference.getKey()).thenReturn(ecPublicKey);

		RadixAddress address = mock(RadixAddress.class);
		when(address.ownsKey(ecPublicKey)).thenReturn(true);

		Particle down0 = mock(Particle.class);
		when(down0.getSpin()).thenReturn(Spin.DOWN);
		when(down0.getAddresses()).thenReturn(Collections.singleton(ecPublicKey));

		Dson dson = mock(Dson.class);
		when(dson.toDson(down0)).thenReturn(new byte[] {0});

		Particle up1 = mock(Particle.class);
		when(up1.getSpin()).thenReturn(Spin.UP);
		when(up1.getAddresses()).thenReturn(Collections.singleton(ecPublicKey));
		when(dson.toDson(up1)).thenReturn(new byte[] {1});

		Atom atom = mock(Atom.class);
		when(atom.particles(Spin.DOWN)).then(inv -> Stream.of(down0));
		when(atom.particles(Spin.UP)).then(inv -> Stream.of(up1));

		Particle up0 = mock(Particle.class);
		when(up0.getSpin()).thenReturn(Spin.UP);
		when(up0.getAddresses()).thenReturn(Collections.singleton(ecPublicKey));
		when(dson.toDson(up0)).thenReturn(new byte[] {0});

		Particle down2 = mock(Particle.class);
		when(down2.getSpin()).thenReturn(Spin.DOWN);
		when(down2.getAddresses()).thenReturn(Collections.singleton(mock(ECPublicKey.class)));
		when(dson.toDson(down2)).thenReturn(new byte[] {2});

		Atom oldAtom = mock(Atom.class);
		when(oldAtom.particles(Spin.DOWN)).then(inv -> Stream.of(down2));
		when(oldAtom.particles(Spin.UP)).then(inv -> Stream.of(up0));

		TestObserver<Atom> observer = TestObserver.create();

		/* Make sure we don't count it unless we find the matching consumable */
		ValidAtomFilter validAtomFilter = new ValidAtomFilter(address, dson);
		validAtomFilter.filter(atom);
		validAtomFilter.filter(oldAtom).subscribe(observer);
		observer.assertValues(oldAtom, atom);
	}
}