package astavie.thermallogistics.tile;

import astavie.thermallogistics.ThermalLogistics;
import astavie.thermallogistics.attachment.DistributorItem;
import astavie.thermallogistics.block.BlockTerminal;
import astavie.thermallogistics.client.gui.GuiTerminalItem;
import astavie.thermallogistics.container.ContainerTerminalItem;
import astavie.thermallogistics.process.Process;
import astavie.thermallogistics.process.ProcessItem;
import astavie.thermallogistics.process.Request;
import astavie.thermallogistics.process.RequestItem;
import astavie.thermallogistics.util.ItemPrint;
import astavie.thermallogistics.util.Shared;
import astavie.thermallogistics.util.StackHandler;
import codechicken.lib.inventory.InventorySimple;
import cofh.core.inventory.InventoryCraftingFalse;
import cofh.core.network.PacketBase;
import cofh.core.network.PacketHandler;
import cofh.core.util.helpers.InventoryHelper;
import cofh.core.util.helpers.ItemHelper;
import cofh.thermaldynamics.duct.attachments.servo.ServoItem;
import cofh.thermaldynamics.duct.item.DuctUnitItem;
import cofh.thermaldynamics.duct.item.GridItem;
import cofh.thermaldynamics.duct.item.StackMap;
import cofh.thermaldynamics.duct.item.TravelingItem;
import cofh.thermaldynamics.duct.tiles.DuctToken;
import com.google.common.collect.Lists;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Triple;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class TileTerminalItem extends TileTerminal<ItemStack> {

	public final Shared.Item[] shared = new Shared.Item[9];

	public final InventorySimple inventory = new InventorySimple(27);
	public final RequestItem requests = new RequestItem(null);

	@Override
	protected void sync(PacketBase packet) {
		packet.addInt(requests.stacks.size());
		for (ItemStack stack : requests.stacks) {
			packet.addItemStack(ItemHelper.cloneStack(stack, 1));
			packet.addInt(stack.getCount());
		}
	}

	@Override
	protected void read(PacketBase packet) {
		requests.stacks.clear();
		int size = packet.getInt();
		for (int i = 0; i < size; i++)
			requests.stacks.add(ItemHelper.cloneStack(packet.getItemStack(), packet.getInt()));
	}

	@Override
	protected void read(PacketBase packet, byte message, EntityPlayer player) {
		if (message == 1) {
			int index = packet.getInt();
			if (index < requests.stacks.size()) {
				ItemStack remove = requests.stacks.remove(index);
				markChunkDirty();

				PacketHandler.sendToAllAround(getSyncPacket(), this);

				int shrink = 0;

				a:
				for (Requester requester : processes) {
					for (Object object : requester.process.getStacks()) {
						ItemStack stack = (ItemStack) object;
						if (ItemHelper.itemsIdentical(stack, remove)) {
							shrink += stack.getCount();
							continue a;
						}
					}
				}

				shrink -= requests.getCount(remove);
				if (shrink <= 0)
					return;

				for (Requester requester : processes) {
					for (Iterator iterator = requester.process.requests.iterator(); iterator.hasNext(); ) {
						//noinspection unchecked
						Request<ItemStack> request = (Request<ItemStack>) iterator.next();

						for (Iterator<ItemStack> iterator1 = request.stacks.iterator(); iterator1.hasNext(); ) {
							ItemStack stack = iterator1.next();
							if (!ItemHelper.itemsIdentical(remove, stack))
								continue;

							int count = Math.min(stack.getCount(), shrink);
							stack.shrink(count);
							shrink -= count;

							if (stack.isEmpty())
								iterator1.remove();

							break;
						}

						if (request.stacks.isEmpty())
							iterator.remove();
						if (shrink == 0)
							return;
					}
				}
			}
		} else if (message == 2) {
			boolean shift = packet.getBool();

			Ingredient[] ingredients = new Ingredient[9];
			for (int i = 0; i < 9; i++) {
				ItemStack[] stacks = new ItemStack[packet.getInt()];
				for (int j = 0; j < stacks.length; j++)
					stacks[j] = packet.getItemStack();
				ingredients[i] = Ingredient.fromStacks(stacks);
			}

			IRecipe recipe = CraftingManager.getRecipe(new ResourceLocation(packet.getString()));
			if (recipe == null)
				return;

			ItemStack craft = recipe.getRecipeOutput().copy();
			ItemStack hand = player.inventory.getItemStack();
			if (!shift && !hand.isEmpty() && (!ItemHelper.itemsIdentical(craft, hand) || craft.getCount() + hand.getCount() > hand.getMaxStackSize()))
				return;

			b:
			//noinspection LoopConditionNotUpdatedInsideLoop
			do {
				// Get available items
				RequestItem items = new RequestItem(null);
				for (int slot = 0; slot < inventory.getSizeInventory(); slot++)
					if (!inventory.getStackInSlot(slot).isEmpty())
						items.addStack(inventory.getStackInSlot(slot));
				for (ItemStack stack : player.inventory.mainInventory)
					if (!stack.isEmpty())
						items.addStack(stack);

				// Check if those items are enough
				a:
				for (Ingredient ingredient : ingredients) {
					if (ingredient == Ingredient.EMPTY)
						continue;
					for (Iterator<ItemStack> iterator = items.stacks.iterator(); iterator.hasNext(); ) {
						ItemStack stack = iterator.next();
						if (ingredient.apply(stack)) {
							stack.shrink(1);
							if (stack.isEmpty())
								iterator.remove();
							continue a;
						}
					}
					break b;
				}

				InventoryCrafting inventory = new InventoryCraftingFalse(3, 3);

				// Craft item
				a:
				for (int i = 0, ingredientsLength = ingredients.length; i < ingredientsLength; i++) {
					Ingredient ingredient = ingredients[i];
					if (ingredient == Ingredient.EMPTY)
						continue;

					for (int slot = 0; slot < this.inventory.getSizeInventory(); slot++) {
						ItemStack stack = this.inventory.getStackInSlot(slot);
						if (ingredient.apply(stack)) {
							ItemStack decreased = this.inventory.decrStackSize(slot, 1);
							inventory.setInventorySlotContents(i, decreased);
							continue a;
						}
					}
					for (int slot = 0; slot < player.inventory.mainInventory.size(); slot++) {
						ItemStack stack = player.inventory.getStackInSlot(slot);
						if (ingredient.apply(stack)) {
							ItemStack decreased = player.inventory.decrStackSize(slot, 1);
							inventory.setInventorySlotContents(i, decreased);
							continue a;
						}
					}
				}

				craft.onCrafting(world, player, 1);
				FMLCommonHandler.instance().firePlayerCraftingEvent(player, craft, inventory);

				if (!recipe.isDynamic()) {
					player.unlockRecipes(Lists.newArrayList(recipe));
				}

				// Add containers
				ForgeHooks.setCraftingPlayer(player);
				NonNullList<ItemStack> ret = recipe.getRemainingItems(inventory);
				ForgeHooks.setCraftingPlayer(null);

				for (ItemStack stack : ret) {
					ItemStack item = InventoryHelper.insertStackIntoInventory(new InvWrapper(this.inventory), stack, false);
					if (!item.isEmpty())
						player.inventory.placeItemBackInInventory(world, item);
				}

				// Add item
				if (shift) {
					ItemStack item = InventoryHelper.insertStackIntoInventory(new InvWrapper(this.inventory), craft, false);
					if (!item.isEmpty())
						player.inventory.placeItemBackInInventory(world, item);
				} else {
					player.inventory.setItemStack(hand.isEmpty() ? craft : ItemHelper.cloneStack(hand, hand.getCount() + craft.getCount()));
					((EntityPlayerMP) player).updateHeldItem();
				}
			} while (shift);

			player.openContainer.detectAndSendChanges();
		} else if (message == 3) {
			if (requester.get().isEmpty())
				return;

			ItemStack hand = player.inventory.getItemStack();

			if (dump(hand)) {
				player.inventory.setItemStack(ItemStack.EMPTY);
				((EntityPlayerMP) player).updateHeldItem();
			}
		} else if (message == 4) {
			for (int i = 0; i < inventory.getSizeInventory(); i++) {
				ItemStack item = inventory.getStackInSlot(i);
				if (item.isEmpty())
					continue;

				item.setCount(InventoryHelper.insertStackIntoInventory(new PlayerMainInvWrapper(player.inventory), item.copy(), false).getCount());
			}
			player.openContainer.detectAndSendChanges();
		} else if (message == 5) {
			if (requester.get().isEmpty())
				return;

			for (int i = 0; i < inventory.getSizeInventory(); i++) {
				ItemStack stack = inventory.getStackInSlot(i);
				if (!stack.isEmpty() && dump(stack))
					inventory.setInventorySlotContents(i, ItemStack.EMPTY);
			}
		}
	}

	private boolean dump(ItemStack stack) {
		int type = requester.get().getMetadata();

		for (Requester requester : processes) {
			DuctUnitItem duct = (DuctUnitItem) requester.getDuct();
			if (duct == null)
				continue;

			//noinspection unchecked
			TravelingItem item = DistributorItem.findRouteForItem(stack, requester.getRoutes(), duct, requester.getSide(), ServoItem.range[type], ServoItem.speedBoost[type]);
			if (item == null)
				continue;

			duct.insertNewItem(item);
			return true;
		}

		return false;
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);

		NBTTagList slots = new NBTTagList();
		for (int i = 0; i < inventory.getSizeInventory(); i++) {
			ItemStack stack = inventory.getStackInSlot(i);
			if (!stack.isEmpty()) {
				NBTTagCompound item = stack.writeToNBT(new NBTTagCompound());
				item.setInteger("slot", i);
				slots.appendTag(item);
			}
		}

		NBTTagList requests = new NBTTagList();
		for (ItemStack stack : this.requests.stacks)
			requests.appendTag(stack.writeToNBT(new NBTTagCompound()));

		nbt.setTag("inventory", slots);
		nbt.setTag("requests", requests);
		return nbt;
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);

		NBTTagList slots = nbt.getTagList("inventory", Constants.NBT.TAG_COMPOUND);
		for (int i = 0; i < slots.tagCount(); i++) {
			NBTTagCompound item = slots.getCompoundTagAt(i);
			inventory.setInventorySlotContents(item.getInteger("slot"), new ItemStack(item));
		}

		NBTTagList requests = nbt.getTagList("requests", Constants.NBT.TAG_COMPOUND);
		for (int i = 0; i < requests.tagCount(); i++)
			this.requests.stacks.add(new ItemStack(requests.getCompoundTagAt(i)));
	}

	@Override
	public Object getGuiClient(InventoryPlayer inventory) {
		return new GuiTerminalItem(this, inventory);
	}

	@Override
	public Object getGuiServer(InventoryPlayer inventory) {
		return new ContainerTerminalItem(this, inventory);
	}

	@Override
	public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing) {
		return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY && facing != world.getBlockState(pos).getValue(BlockTerminal.DIRECTION).getFace() || super.hasCapability(capability, facing);
	}

	@Nullable
	@Override
	public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
		if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY && facing != world.getBlockState(pos).getValue(BlockTerminal.DIRECTION).getFace())
			return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(new Inventory(this, inventory));
		return super.getCapability(capability, facing);
	}

	@Override
	public String getTileName() {
		return ThermalLogistics.Blocks.terminal_item.getTranslationKey() + ".name";
	}

	@Override
	protected DuctToken<?, ?, ?> getDuctToken() {
		return DuctToken.ITEMS;
	}

	@Override
	protected Process<ItemStack> createProcess(Requester<ItemStack> requester) {
		return new ProcessItem(requester);
	}

	@Override
	protected int amountRequired(ItemStack stack) {
		int required = requests.getCount(stack);
		if (required == 0)
			return 0;

		for (Requester request : processes) {
			DuctUnitItem duct = (DuctUnitItem) request.getDuct();
			if (duct == null)
				continue;

			//noinspection unchecked
			Process<ItemStack> process = request.process;

			// Items in requests
			for (ItemStack item : process.getStacks())
				if (ItemHelper.itemsIdentical(item, stack))
					required -= item.getCount();

			// Items traveling
			StackMap map = duct.getGrid().travelingItems.get(pos);
			if (map != null)
				for (ItemStack item : map.getItems())
					if (ItemHelper.itemsIdentical(item, stack))
						required -= item.getCount();
		}

		return required;
	}

	@Override
	protected void request(PacketBase payload) {
		requests.addStack(ItemHelper.cloneStack((ItemStack) StackHandler.readPacket(payload), payload.getInt()));
	}

	@Override
	protected void updateTerminal() {
		terminal.clear();

		Set<GridItem> grids = new HashSet<>();
		Set<IItemHandler> handlers = new HashSet<>();

		Map<ItemPrint, MutablePair<Long, Boolean>> total = new HashMap<>();

		for (Requester requester : processes) {
			DuctUnitItem duct = (DuctUnitItem) requester.getDuct();
			if (duct == null || grids.contains(duct.getGrid()))
				continue;

			//noinspection unchecked
			Map<ItemPrint, MutablePair<Long, Boolean>> list = StackHandler.getItems((Requester<ItemStack>) requester, handlers);
			for (Map.Entry<ItemPrint, MutablePair<Long, Boolean>> entry : list.entrySet()) {
				MutablePair<Long, Boolean> pair = total.get(entry.getKey());
				if (pair != null) {
					pair.left += entry.getValue().left;
					pair.right = pair.right || entry.getValue().right;
				} else {
					total.put(entry.getKey(), entry.getValue());
				}
			}

			grids.add(duct.getGrid());
		}

		terminal.clear();
		for (Map.Entry<ItemPrint, MutablePair<Long, Boolean>> entry : total.entrySet())
			terminal.add(Triple.of(entry.getKey().compare, entry.getValue().left, entry.getValue().right));
	}

	@Override
	protected boolean hasRequests() {
		return !requests.stacks.isEmpty();
	}

	@Override
	public Class<ItemStack> getItemClass() {
		return ItemStack.class;
	}

	private static class Inventory extends InvWrapper {

		private final TileTerminalItem tile;

		private Inventory(TileTerminalItem tile, IInventory inv) {
			super(inv);
			this.tile = tile;
		}

		@Nonnull
		@Override
		public ItemStack extractItem(int slot, int amount, boolean simulate) {
			return ItemStack.EMPTY;
		}

		@Nonnull
		@Override
		public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
			ItemStack insert = ItemHelper.cloneStack(stack, Math.min(stack.getCount(), tile.requests.getCount(stack)));
			if (insert.isEmpty())
				return stack;

			ItemStack remainder = super.insertItem(slot, insert, simulate);
			if (!simulate) {
				tile.requests.decreaseStack(ItemHelper.cloneStack(insert, insert.getCount() - remainder.getCount()));
				PacketHandler.sendToAllAround(tile.getSyncPacket(), tile);
			}

			return ItemHelper.cloneStack(stack, stack.getCount() - insert.getCount() + remainder.getCount());
		}

	}

}
