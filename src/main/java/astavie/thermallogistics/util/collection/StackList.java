package astavie.thermallogistics.util.collection;

import astavie.thermallogistics.util.type.Type;
import cofh.core.network.PacketBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.stream.Collectors;

public abstract class StackList<S> {

	private final Map<Type<S>, Pair<Long, Boolean>> map = new LinkedHashMap<>();

	public abstract Type<S> getType(S stack);

	protected abstract int getAmount(S stack);

	public void add(S stack) {
		map.compute(getType(stack), (t, p) -> Pair.of(
				(p == null ? 0L : p.getLeft()) + getAmount(stack),
				p == null ? false : p.getRight()
		));
	}

	public void addAll(StackList<S> list) {
		for (Map.Entry<Type<S>, Pair<Long, Boolean>> entry : list.map.entrySet()) {
			map.compute(entry.getKey(), (t, p) -> Pair.of(
					(p == null ? 0L : p.getLeft()) + entry.getValue().getLeft(),
					(p == null ? false : p.getRight()) || entry.getValue().getRight()
			));
		}
	}

	public void addCraftable(Type<S> type) {
		map.compute(type, (t, p) -> Pair.of(
				p == null ? 0L : p.getLeft(),
				true
		));
	}

	public int remove(S stack) {
		Type<S> type = getType(stack);
		Pair<Long, Boolean> amount = map.get(type);
		if (amount == null)
			return getAmount(stack);

		if (getAmount(stack) < amount.getLeft()) {
			map.put(type, Pair.of(amount.getLeft() - getAmount(stack), amount.getRight()));
			return 0;
		} else {
			if (amount.getRight())
				map.put(type, Pair.of(0L, true));
			else
				map.remove(type);

			return (int) (getAmount(stack) - amount.getLeft());
		}
	}

	public Pair<Type<S>, Long> remove(int index) {
		Iterator<Map.Entry<Type<S>, Pair<Long, Boolean>>> iterator = map.entrySet().iterator();
		for (int i = 0; i < index; i++)
			iterator.next();

		Map.Entry<Type<S>, Pair<Long, Boolean>> entry = iterator.next();
		iterator.remove();

		return Pair.of(entry.getKey(), entry.getValue().getLeft());
	}

	public int size() {
		return map.size();
	}

	public boolean isEmpty() {
		return map.isEmpty();
	}

	public void clear() {
		map.clear();
	}

	public long amount(Type<S> type) {
		return map.getOrDefault(type, Pair.of(0L, false)).getLeft();
	}

	public long amount(S stack) {
		return amount(getType(stack));
	}

	public boolean craftable(Type<S> type) {
		return map.getOrDefault(type, Pair.of(0L, false)).getRight();
	}

	public boolean craftable(S stack) {
		return craftable(getType(stack));
	}

	public Set<Type<S>> types() {
		return Collections.unmodifiableSet(map.keySet());
	}

	public List<S> stacks() {
		return map.entrySet().stream().map(e -> e.getKey().withAmount(Math.toIntExact(e.getValue().getLeft()))).collect(Collectors.toList());
	}

	protected abstract void writeType(Type<S> type, PacketBase packet);

	protected abstract Type<S> readType(PacketBase packet);

	protected abstract NBTTagCompound writeType(Type<S> type);

	protected abstract Type<S> readType(NBTTagCompound tag);

	public void writePacket(PacketBase packet) {
		packet.addInt(map.size());
		for (Map.Entry<Type<S>, Pair<Long, Boolean>> entry : map.entrySet()) {
			writeType(entry.getKey(), packet);
			packet.addLong(entry.getValue().getLeft());
			packet.addBool(entry.getValue().getRight());
		}
	}

	public void readPacket(PacketBase packet) {
		map.clear();

		int size = packet.getInt();
		for (int i = 0; i < size; i++) {
			map.put(readType(packet), Pair.of(packet.getLong(), packet.getBool()));
		}
	}

	public NBTTagList writeNbt() {
		NBTTagList list = new NBTTagList();
		for (Map.Entry<Type<S>, Pair<Long, Boolean>> entry : map.entrySet()) {
			NBTTagCompound nbt = writeType(entry.getKey());
			nbt.setLong("Count", entry.getValue().getLeft());
			list.appendTag(nbt);
		}
		return list;
	}

	public void readNbt(NBTTagList list) {
		map.clear();

		for (int i = 0; i < list.tagCount(); i++) {
			NBTTagCompound tag = list.getCompoundTagAt(i);
			map.put(readType(tag), Pair.of(tag.getLong("Count"), false));
		}
	}

}
