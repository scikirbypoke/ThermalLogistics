package astavie.thermallogistics.process;

import astavie.thermallogistics.attachment.ICrafter;
import astavie.thermallogistics.attachment.IRequester;
import astavie.thermallogistics.util.RequesterReference;
import astavie.thermallogistics.util.Shared;
import astavie.thermallogistics.util.Snapshot;
import astavie.thermallogistics.util.collection.MissingList;
import astavie.thermallogistics.util.collection.StackList;
import astavie.thermallogistics.util.type.Type;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

public abstract class Process<I> {

	protected final IProcessRequester<I> requester;

	public Process(IProcessRequester<I> requester) {
		this.requester = requester;
	}

	public boolean update(boolean onlyCheck) {
		Map<RequesterReference<I>, StackList<I>> requests = requester.getRequests();

		// Update requests

		for (RequesterReference<I> source : requests.keySet()) {
			if (source == null) {
				if (!onlyCheck && updateRetrieval(requests.get(null))) return true;
				continue;
			}

			StackList<I> list = requests.get(source);
			IRequester<I> r = source.get();

			if (!(r instanceof ICrafter)) {
				// Crafter got removed
				for (Type<I> type : list.types()) {
					requester.onFail(source, type, list.amount(type));
				}
				return true;
			}

			ICrafter<I> crafter = (ICrafter<I>) r;

			if (!crafter.isEnabled() || !crafter.hasRouteTo(requester)) {
				// Crafter got disabled or disconnected
				for (Type<I> type : list.types()) {
					requester.onFail(source, type, list.amount(type));
				}
				return true;
			}

			boolean b = false;
			for (Type<I> type : list.types()) {
				long remove = list.amount(type) - crafter.reserved(requester, type);
				if (remove > 0) {
					// Crafter cancelled without telling us D:<
					requester.onFail(source, type, remove);
					b = true;
				}
			}
			if (b) return true;

			if (!onlyCheck && attemptPull(crafter, list)) {
				return true;
			}
		}

		// Add requests

		return !onlyCheck && requester.hasWants() && updateWants();
	}

	protected abstract boolean updateRetrieval(StackList<I> requests);

	protected abstract boolean updateWants();

	protected abstract boolean attemptPull(ICrafter<I> crafter, StackList<I> stacks);

	/**
	 * Used in terminals: request items
	 */
	public List<Request<I>> request(Type<I> type, long amount, @Nullable Function<Type<I>, Long> func) {
		List<Request<I>> requests = new LinkedList<>();

		// CHECK FOR STACKS

		StackList<I> stacks = Snapshot.INSTANCE.getStacks(requester.getDuct().getGrid());

		long am;
		if (func == null) {
			am = stacks.amount(type);
		} else {
			am = func.apply(type);
		}

		long removed = Math.min(am, amount);
		if (removed > 0) {
			stacks.remove(type, removed);
			amount -= removed;

			requests.add(new Request<>(type, removed, new Source<>(requester.getSide()), 0));
		}

		// CHECK FOR CRAFTERS

		if (amount > 0) {
			Shared<Long> shared = new Shared<>(amount);
			requestFromCrafters(requests, type, shared);
			amount = shared.get();
		}

		// WRAP UP

		if (amount > 0) {
			MissingList list = new MissingList();
			list.add(type, amount);
			requests.add(new Request<>(type, amount, 0, list, false));
		}

		return requests;
	}

	/**
	 * Used in crafters: request missing items
	 */
	public long request(Type<I> type, long amount) {
		long requested = 0;

		// CHECK FOR STACKS

		StackList<I> stacks = Snapshot.INSTANCE.getStacks(requester.getDuct().getGrid());
		long am = stacks.amount(type);

		long removed = Math.min(am, amount);
		if (removed > 0) {
			stacks.remove(type, removed);
			amount -= removed;

			requested += removed;
		}

		// CHECK FOR CRAFTERS

		if (amount > 0) {
			Shared<Long> shared = new Shared<>(amount);

			ICrafter<I> crafter = getCrafter(type);
			if (crafter != null) {
				crafter.request(requester, type, shared, true);
			}

			requested += amount - shared.get();
		}

		return requested;
	}

	public abstract void findCrafter(Predicate<ICrafter<I>> predicate);

	public ICrafter<I> getCrafter(Type<I> output) {
		Shared<ICrafter<I>> shared = new Shared<>();

		findCrafter(crafter -> {
			if (crafter.getOutputs().types().contains(output)) {
				shared.accept(crafter);
				return true;
			}
			return false;
		});

		return shared.get();
	}

	public Pair<ICrafter<I>, Type<I>> getCrafter(Type<I> output, boolean ignoreMod, boolean ignoreOreDict, boolean ignoreMetadata, boolean ignoreNbt) {
		Shared<Pair<ICrafter<I>, Type<I>>> shared = new Shared<>();

		findCrafter(crafter -> {
			for (Type<I> type : crafter.getOutputs().types()) {
				if (type.isIdentical(output, ignoreMod, ignoreOreDict, ignoreMetadata, ignoreNbt)) {
					shared.accept(Pair.of(crafter, type));
					return true;
				}
			}
			return false;
		});

		return shared.get();
	}

	/**
	 * Used in terminal: request from crafters
	 */
	private void requestFromCrafters(List<Request<I>> requests, Type<I> type, Shared<Long> amount) {
		ICrafter<I> crafter = getCrafter(type);
		if (crafter != null) {
			request(requests, crafter, type, amount);
		}
	}

	/**
	 * Request item from crafter
	 */
	protected boolean request(List<Request<I>> requests, ICrafter<I> crafter, Type<I> type, Shared<Long> amount) {
		// Do the thing
		long a = amount.get();

		MissingList missing = crafter.request(requester, type, amount, false);

		if (amount.get() < a) {
			requests.add(new Request<>(type, a - amount.get(), new Source<>(requester.getSide(), crafter.createReference()), 0));
		}

		if (missing == null) {
			// Complex
			requests.add(new Request<>(type, amount.get(), 0, null, true));
		} else if (!missing.isEmpty()) {
			// Missing
			requests.add(new Request<>(type, amount.get(), 0, missing, false));
		}

		amount.accept(0L);
		return true;
	}

}
