package astavie.thermallogistics.process;

import astavie.thermallogistics.attachment.ICrafter;
import astavie.thermallogistics.attachment.IRequester;
import astavie.thermallogistics.util.RequesterReference;
import cofh.core.util.helpers.InventoryHelper;
import cofh.core.util.helpers.ItemHelper;
import cofh.thermaldynamics.duct.Attachment;
import cofh.thermaldynamics.duct.item.DuctUnitItem;
import cofh.thermaldynamics.duct.item.GridItem;
import cofh.thermaldynamics.duct.item.TravelingItem;
import cofh.thermaldynamics.multiblock.IGridTileRoute;
import cofh.thermaldynamics.multiblock.Route;
import cofh.thermaldynamics.util.ListWrapper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraftforge.items.IItemHandler;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

public class ProcessItem extends Process<ItemStack> {

	public ProcessItem(IRequester<ItemStack> requester) {
		super(requester);
	}

	public static boolean checkRequests(IRequester<ItemStack> requester, List<Request<ItemStack>> requests, BiFunction<IRequester<ItemStack>, IRequester<ItemStack>, List<ItemStack>> function) {
		boolean changed = false;
		for (Iterator<Request<ItemStack>> iterator = requests.iterator(); iterator.hasNext(); ) {
			Request<ItemStack> request = iterator.next();
			if (request.attachment.isLoaded()) {
				IRequester<ItemStack> attachment = request.attachment.getAttachment();
				if (attachment == null || !attachment.isEnabled()) {
					iterator.remove();
					requester.markDirty();
				} else {
					List<ItemStack> list = function.apply(attachment, requester);

					a:
					for (Iterator<ItemStack> iterator1 = request.stacks.iterator(); iterator1.hasNext(); ) {
						ItemStack stack = iterator1.next();
						for (ItemStack compare : list) {
							if (ItemHelper.itemsIdentical(stack, compare)) {
								if (stack.getCount() > compare.getCount()) {
									stack.setCount(compare.getCount());
									changed = true;
									requester.markDirty();
								}
								continue a;
							}
						}

						iterator1.remove();
						requester.markDirty();
					}

					if (request.stacks.isEmpty()) {
						iterator.remove();
						requester.markDirty();
					}
				}
			}
		}
		return changed;
	}

	@Override
	public NBTTagList writeNbt() {
		NBTTagList requests = new NBTTagList();
		for (Request<ItemStack> request : this.requests)
			requests.appendTag(RequestItem.writeNBT(request));
		return requests;
	}

	@Override
	public void readNbt(NBTTagList nbt) {
		requests.clear();
		for (int i = 0; i < nbt.tagCount(); i++)
			requests.add(RequestItem.readNBT(nbt.getCompoundTagAt(i)));
	}

	private static Pair<ItemStack, Route> extract(IRequester<ItemStack> requester, IItemHandler handler, Function<ItemStack, Integer> amountRequired, DuctUnitItem endPoint, DuctUnitItem.Cache cache, IItemHandler inv) {
		for (int slot = 0; slot < inv.getSlots(); slot++) {
			ItemStack item = inv.getStackInSlot(slot);
			if (item.isEmpty())
				continue;

			if (cache != null && !cache.filter.matchesFilter(item))
				continue;

			int amount = Math.min(amountRequired.apply(item), requester.getMaxSend());
			if (amount == 0)
				continue;

			item = ItemHelper.cloneStack(item, requester.hasMultiStack() ? Integer.MAX_VALUE : item.getCount());
			if (item.isEmpty())
				continue;

			item = checkItem(requester, handler, item, amount);
			if (item.isEmpty())
				continue;

			Route route1 = endPoint.getRoute((IGridTileRoute) requester.getDuct());
			if (route1 == null)
				continue;

			int maxStackSize = item.getCount();
			item = inv.extractItem(slot, maxStackSize, false);
			if (item.isEmpty())
				continue;

			// No turning back now
			route1 = route1.copy();
			route1.pathDirections.add(requester.getSide());

			if (requester.hasMultiStack() && item.getCount() < maxStackSize) {
				for (; item.getCount() < maxStackSize && slot < inv.getSlots(); slot++) {
					if (ItemHelper.itemsEqualWithMetadata(inv.getStackInSlot(slot), item, true)) {
						ItemStack extract = inv.extractItem(slot, maxStackSize - item.getCount(), false);
						if (!extract.isEmpty()) {
							item.grow(extract.getCount());
						}
					}
				}
			}

			requester.refreshCache();
			return Pair.of(item, route1);
		}
		return null;
	}

	private static ItemStack checkItem(IRequester<ItemStack> requester, IItemHandler handler, ItemStack item, int amount) {
		ItemStack remainder = item.copy();

		if (remainder.getCount() <= amount) {
			remainder = InventoryHelper.insertStackIntoInventory(handler, remainder, true);
		} else {
			ItemStack remaining = InventoryHelper.insertStackIntoInventory(handler, remainder.splitStack(amount), true);
			if (!remaining.isEmpty()) {
				remainder.grow(remaining.getCount());
			}
		}

		if (!remainder.isEmpty())
			item.shrink(remainder.getCount());

		if (item.getCount() <= 0)
			return ItemStack.EMPTY;

		int remain = ((DuctUnitItem) requester.getDuct()).canRouteItem(item, requester.getSide());
		if (remain == -1)
			return ItemStack.EMPTY;

		item.shrink(remain);
		return item;
	}

	@Override
	public void tick() {
		if (!requester.hasRequests() || (requester.getDuct().world().getTotalWorldTime() - offset) % requester.tickDelay() != 0)
			return;

		// Check requests
		checkRequests(requester, requests, IRequester::getOutputTo);

		TileEntity tile = requester.getCachedTile();
		if (tile == null)
			return;

		IItemHandler handler = InventoryHelper.getItemHandlerCap(tile, EnumFacing.byIndex(requester.getSide() ^ 1));
		if (handler == null)
			return;

		for (Iterator<Request<ItemStack>> iterator = requests.iterator(); iterator.hasNext(); ) {
			Request<ItemStack> request = iterator.next();
			if (request.stacks.isEmpty()) {
				iterator.remove();
				continue;
			}

			if (!request.attachment.isLoaded())
				continue;

			IRequester<ItemStack> requester = request.attachment.getAttachment();
			if (requester == null)
				continue;

			DuctUnitItem endPoint = (DuctUnitItem) requester.getDuct();
			byte side = requester.getSide();

			DuctUnitItem.Cache cache = endPoint.tileCache[side];
			if (cache == null || (!endPoint.isInput(side) && !endPoint.isOutput(side)) || !endPoint.parent.getConnectionType(side).allowTransfer)
				continue;

			IItemHandler inv = cache.getItemHandler(side ^ 1);
			if (inv == null)
				continue;

			Pair<ItemStack, Route> extract = extract(this.requester, handler, request::getCount, endPoint, null, inv);
			if (extract != null) {
				requester.onFinishCrafting(this.requester, extract.getLeft());

				request.decreaseStack(extract.getLeft());
				if (request.stacks.isEmpty())
					iterator.remove();

				endPoint.insertNewItem(new TravelingItem(extract.getLeft(), endPoint, extract.getRight(), (byte) (side ^ 1), this.requester.getSpeed()));

				this.requester.markDirty();
				return;
			}
		}

		// Check items
		List<ICrafter<ItemStack>> crafters = NonNullList.create();

		ListWrapper<Route<DuctUnitItem, GridItem>> routes = requester.getRoutes();
		for (Route route : routes) {
			DuctUnitItem endPoint = (DuctUnitItem) route.endPoint;

			byte side = route.getLastSide();

			Attachment attachment = endPoint.parent.getAttachment(side);
			if (attachment instanceof ICrafter)
				//noinspection unchecked
				crafters.add((ICrafter<ItemStack>) attachment);

			if (attachment != null && !attachment.canSend())
				continue;

			DuctUnitItem.Cache cache = endPoint.tileCache[side];
			if (cache == null)
				continue;

			if (cache.tile instanceof ICrafter)
				//noinspection unchecked
				crafters.add((ICrafter<ItemStack>) cache.tile);

			if ((!endPoint.isInput(side) && !endPoint.isOutput(side)) || !endPoint.parent.getConnectionType(side).allowTransfer)
				continue;

			IItemHandler inv = cache.getItemHandler(side ^ 1);
			if (inv == null || inv.equals(handler))
				continue;

			Pair<ItemStack, Route> extract = extract(requester, handler, requester::amountRequired, endPoint, cache, inv);
			if (extract != null) {
				endPoint.insertNewItem(new TravelingItem(extract.getLeft(), endPoint, extract.getRight(), (byte) (side ^ 1), requester.getSpeed()));
				routes.advanceCursor();
				return;
			}
		}

		// Check crafters
		for (ICrafter<ItemStack> crafter : crafters) {
			if (crafter == requester || !crafter.isEnabled())
				continue;

			Set<RequesterReference<ItemStack>> blacklist = crafter.getBlacklist();
			if (blacklist.stream().anyMatch(reference -> reference.references(requester)))
				continue;

			for (ItemStack stack : crafter.getOutputs()) {
				int amount = requester.amountRequired(stack);
				if (amount == 0)
					continue;

				amount = Math.min(amount, requester.getMaxSend() - getCount(stack));
				if (amount == 0)
					continue;

				stack = ItemHelper.cloneStack(stack, amount);

				// stack = checkItem(requester, handler, stack, amount);
				// if (stack.isEmpty())
				//	continue;

				if (!crafter.request(requester, stack))
					continue;

				// No turning back now
				requester.markDirty();

				for (Request<ItemStack> request : requests) {
					if (request.attachment.references(crafter)) {
						request.addStack(stack);
						request.blacklist.addAll(blacklist);
						return;
					}
				}

				Request<ItemStack> request = new RequestItem(crafter.getReference(), stack);
				request.blacklist.add(crafter.getReference());
				request.blacklist.addAll(blacklist);

				requests.add(request);
				return;
			}
		}
	}

}
