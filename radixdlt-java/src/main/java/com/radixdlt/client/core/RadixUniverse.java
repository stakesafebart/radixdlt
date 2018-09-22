package com.radixdlt.client.core;

import com.radixdlt.client.application.translate.ConsumableDataSource;
import com.radixdlt.client.core.address.EUID;
import com.radixdlt.client.core.address.RadixAddress;
import com.radixdlt.client.core.address.RadixUniverseConfig;
import com.radixdlt.client.core.atoms.Atom;
import com.radixdlt.client.core.atoms.Consumable;
import com.radixdlt.client.core.crypto.ECPublicKey;
import com.radixdlt.client.core.ledger.AtomFetcher;
import com.radixdlt.client.core.ledger.AtomPuller;
import com.radixdlt.client.core.ledger.AtomSubmitter;
import com.radixdlt.client.core.ledger.ClientSelector;
import com.radixdlt.client.core.ledger.InMemoryAtomStore;
import com.radixdlt.client.core.network.AtomSubmissionUpdate;
import com.radixdlt.client.core.network.PeerDiscovery;
import com.radixdlt.client.core.network.RadixNetwork;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import java.util.Collection;
import java.util.function.Function;

/**
 * A RadixUniverse represents the interface through which a client can interact
 * with a Radix Universe (an instance of a Radix Ledger + Radix Network).
 * <p>
 * The configuration file of a Radix Universe defines the genesis atoms of the
 * distributed getLedger and distinguishes this universe from other universes.
 * (e.g. Public net vs Test net) It is shared amongst all participants of this
 * instance of the Radix getNetwork.
 * <p>
 * The network interface is available to directly connect with Node Runners in
 * the Radix Network in order to read/write atoms on the distributed ledger.
 * <p>
 * The ledger interface is a thin wrapper on the network interface which provides
 * an abstraction which doesn't require managing network peers. It can for example
 * be used to cache atoms locally.
 */
public final class RadixUniverse {

	/**
	 * Lock to protect default Radix Universe instance
	 */
	private static Object lock = new Object();

	/**
	 * Default Universe Instance
	 */
	private static RadixUniverse defaultUniverse;


	/**
	 * Initializes the default universe with a Peer Discovery mechanism.
	 * Should only be called once at the start of the program.
	 *
	 * @param peerDiscovery The peer discovery mechanism
	 * @return The default universe created, can also be retrieved with RadixUniverse.getInstance()
	 */
	public static RadixUniverse bootstrap(
		RadixUniverseConfig config,
		PeerDiscovery peerDiscovery
	) {
		synchronized (lock) {
			if (defaultUniverse != null) {
				throw new IllegalStateException("Default Universe already bootstrapped");
			}

			RadixNetwork network = new RadixNetwork(peerDiscovery);

			defaultUniverse = new RadixUniverse(config, network);

			return defaultUniverse;
		}
	}

	public static RadixUniverse bootstrap(BootstrapConfig bootstrapConfig) {
		return bootstrap(bootstrapConfig.getConfig(), bootstrapConfig.getDiscovery());
	}

	/**
	 * Returns the default RadixUniverse instance
	 * @return the default RadixUniverse instance
	 */
	public static RadixUniverse getInstance() {
		synchronized (lock) {
			if (defaultUniverse == null) {
				throw new IllegalStateException("Default Universe was not initialized via RadixUniverse.bootstrap()");
			}
			return defaultUniverse;
		}
	}

	/**
	 * Network Interface
	 */
	private final RadixNetwork network;

	/**
	 * Universe Configuration
	 */
	private final RadixUniverseConfig config;

	/**
	 * The Particle Data Store
	 * TODO: actually change it into the particle data store
	 */
	private final ConsumableDataSource consumableDataSource;

	private final ClientSelector clientSelector;
	private final AtomFetcher atomFetcher;
	private final AtomPuller atomPuller;
	private final AtomSubmitter atomSubmitter;
	private final InMemoryAtomStore inMemoryAtomStore;

	private RadixUniverse(RadixUniverseConfig config, RadixNetwork network) {
		this.config = config;
		this.network = network;
		this.clientSelector = new ClientSelector(config, network);
		this.atomFetcher = new AtomFetcher(clientSelector::getRadixClient);
		this.inMemoryAtomStore = new InMemoryAtomStore();
		this.atomPuller = new AtomPuller(atomFetcher::fetchAtoms, inMemoryAtomStore::store);
		this.atomSubmitter = new AtomSubmitter(clientSelector::getRadixClient);
		this.consumableDataSource = new ConsumableDataSource(inMemoryAtomStore::getAtoms);
	}

	public int getMagic() {
		return config.getMagic();
	}

	public Function<RadixAddress, Observable<Collection<Consumable>>> getParticleStore() {
		return consumableDataSource::getConsumables;
	}

	public Function<EUID, Observable<Atom>> getAtomStore() {
		return euid -> {
			Disposable disposable = atomPuller.pull(euid);
			return inMemoryAtomStore.getAtoms(euid).doOnDispose(disposable::dispose);
		};
	}

	public Function<Atom, Observable<AtomSubmissionUpdate>> getAtomSubmissionHandler() {
		return atomSubmitter::submitAtom;
	}

	public RadixNetwork getNetwork() {
		return network;
	}

	/**
	 * Returns the system public key, also defined as the creator of this Universe
	 *
	 * @return the system public key
	 */
	public ECPublicKey getSystemPublicKey() {
		return config.getSystemPublicKey();
	}

	/**
	 * Maps a public key to it's corresponding Radix address in this universe.
	 * Within a universe, a public key has a one to one bijective relationship to an address
	 *
	 * @param publicKey the key to get an address from
	 * @return the corresponding address to the key for this universe
	 */
	public RadixAddress getAddressFrom(ECPublicKey publicKey) {
		return new RadixAddress(config, publicKey);
	}

	/**
	 * Attempts to gracefully free all resources associated with this Universe
	 */
	public void disconnect() {
		network.close();
	}

	public RadixUniverseConfig getConfig() {
		return config;
	}
}
