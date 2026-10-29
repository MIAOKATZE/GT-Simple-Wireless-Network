package com.miaokatze.gtswn.common.gui;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.utils.item.InvWrapper;
import com.cleanroommc.modularui.utils.item.PlayerInvWrapper;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.cleanroommc.modularui.widgets.slot.PlayerSlotGroup;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;

import gregtech.common.misc.WirelessNetworkManager;
import gregtech.common.misc.spaceprojects.SpaceProjectManager;

/** A bounded real item slot, with matching client prediction and integer persistent reserves. */
public class ContainerTerminal extends ModularContainer {

    private static final AtomicLong NEXT_RENAME_TOKEN = new AtomicLong(System.nanoTime());
    private static final long RENAME_TTL_NANOS = 60_000_000_000L;
    public final boolean quantum;
    public final TerminalSupplyStore.Kind kind;
    private final EntityPlayer owner;
    private final ItemStack terminal;
    private final String colorBinding;
    private final ItemStack supplyReference;
    private final int heldIndex;
    private final List<ModularSlot> terminalSlots = new ArrayList<>();
    private final boolean deferSlotRegistration;
    private final int[] values = new int[84];
    private final int[] sent = new int[84];
    private long renameToken;
    private long renameIssuedNanos;
    private boolean closed;
    private long snapshotTick = Long.MIN_VALUE;
    public int supplyCount;

    public ContainerTerminal(EntityPlayer player, boolean quantum) {
        this(player, quantum, (quantum ? TerminalSupplyStore.Kind.ANCHOR : TerminalSupplyStore.Kind.TUBE).reference());
    }

    ContainerTerminal(EntityPlayer player, boolean quantum, ItemStack reference) {
        this(player, quantum, reference, false);
    }

    static ContainerTerminal forUi(EntityPlayer player, boolean quantum) {
        return new ContainerTerminal(
            player,
            quantum,
            (quantum ? TerminalSupplyStore.Kind.ANCHOR : TerminalSupplyStore.Kind.TUBE).reference(),
            true);
    }

    ContainerTerminal(EntityPlayer player, boolean quantum, ItemStack reference, boolean deferSlotRegistration) {
        this.deferSlotRegistration = deferSlotRegistration;
        this.owner = player;
        this.quantum = quantum;
        kind = quantum ? TerminalSupplyStore.Kind.ANCHOR : TerminalSupplyStore.Kind.TUBE;
        supplyReference = reference == null ? null : reference.copy();
        terminal = player.getHeldItem();
        colorBinding = colorBinding(terminal);
        heldIndex = player.inventory.currentItem;
        if (quantum && !player.worldObj.isRemote) renewRenameToken();
        Arrays.fill(sent, -1);
        addTerminalSlot(new ModularSlot(new InvWrapper(new InventoryBasic("supply", false, 1)), 0) {

            @Override
            public boolean isItemValid(ItemStack stack) {
                return matchesSupply(stack);
            }

            @Override
            public boolean canTakeStack(EntityPlayer player) {
                return true;
            }
        }, 279, 161);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++)
                addPlayerSlot(column + row * 9 + 9, 10 + column * 18, 150 + row * 18);
        }
        for (int column = 0; column < 9; column++) addPlayerSlot(column, 10 + column * 18, 208);
        if (!player.worldObj.isRemote) supplyCount = TerminalSupplyStore.count(player, kind);
        refreshSupplySlot();
    }

    private void addPlayerSlot(int index, int x, int y) {
        addTerminalSlot(new ModularSlot(new PlayerInvWrapper(owner.inventory), index) {

            @Override
            public boolean canTakeStack(EntityPlayer player) {
                return index != heldIndex;
            }

            @Override
            public boolean isItemValid(ItemStack stack) {
                return index != heldIndex;
            }
        }.slotGroup(PlayerSlotGroup.NAME), x, y);
    }

    private void addTerminalSlot(ModularSlot slot, int x, int y) {
        slot.xDisplayPosition = x;
        slot.yDisplayPosition = y;
        terminalSlots.add(slot);
        if (!deferSlotRegistration) addSlotToContainer(slot);
    }

    ModularSlot terminalSlot(int index) {
        return terminalSlots.get(index);
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        ItemStack held = player.getHeldItem();
        return player == owner && player.isEntityAlive()
            && held != null
            && player.inventory.currentItem == heldIndex
            && (player.worldObj.isRemote || held == terminal)
            && (!quantum || player.worldObj.isRemote || QuantumTerminalAccess.canOpenTerminal(player, held))
            && (quantum ? held.getItem() instanceof ItemNetworkQuantumTerminal
                : held.getItem() instanceof WirelessEnergyTap);
    }

    public int heldIndex() {
        return heldIndex;
    }

    EntityPlayer terminalOwner() {
        return owner;
    }

    /** Native progress updates carry four unsigned 16-bit parts, including on vanilla's signed-short wire. */
    public long renameToken() {
        long token = 0;
        for (int i = 0; i < 4; i++) token |= (long) (values[80 + i] & 0xFFFF) << (16 * i);
        return token;
    }

    private void renewRenameToken() {
        renameToken = NEXT_RENAME_TOKEN.incrementAndGet();
        if (renameToken == 0) renameToken = NEXT_RENAME_TOKEN.incrementAndGet();
        renameIssuedNanos = System.nanoTime();
        for (int i = 0; i < 4; i++) values[80 + i] = (int) (renameToken >>> (16 * i)) & 0xFFFF;
    }

    /** The name belongs to the held item, so this intentionally does not require network SECURITY access. */
    private boolean checkRenameContext(EntityPlayer player, int window, int slot, long token, String expectedName) {
        return quantum && !closed
            && !owner.worldObj.isRemote
            && player == owner
            && player.isEntityAlive()
            && player.openContainer == this
            && windowId == window
            && heldIndex == slot
            && player.inventory.currentItem == heldIndex
            && terminal != null
            && terminal.getItem() instanceof ItemNetworkQuantumTerminal
            && player.getHeldItem() == terminal
            && token != 0
            && token == renameToken
            && System.nanoTime() - renameIssuedNanos <= RENAME_TTL_NANOS
            && customName(terminal).equals(expectedName);
    }

    /** Palette intents refer to this exact open terminal and binding, not whatever later occupies its slot. */
    public boolean matchesColorContext(EntityPlayer player) {
        return quantum && !closed
            && player == owner
            && !owner.worldObj.isRemote
            && player.openContainer == this
            && player.isEntityAlive()
            && player.inventory.currentItem == heldIndex
            && terminal != null
            && terminal.getItem() instanceof ItemNetworkQuantumTerminal
            && player.getHeldItem() == terminal
            && colorBinding.equals(colorBinding(terminal));
    }

    private static String colorBinding(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemNetworkQuantumTerminal)) return "";
        return Arrays.toString(ItemNetworkQuantumTerminal.getAnchor(stack)) + ":"
            + (stack.hasTagCompound() ? stack.getTagCompound()
                .getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION) + ":"
                + stack.getTagCompound().getString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY) + ":"
                + stack.getTagCompound().getLong(QuantumTerminalAccess.NBT_BINDING_EPOCH) : "");
    }

    public static String customName(ItemStack stack) {
        return stack != null && stack.hasDisplayName() ? stack.getDisplayName() : "";
    }

    /** Server-thread-only mutation, followed by vanilla slot synchronization. */
    public boolean renameTerminal(EntityPlayer player, int window, int slot, long token, String expectedName,
        String name) {
        if (!checkRenameContext(player, window, slot, token, expectedName)
            || !com.miaokatze.gtswn.common.monitoring.QuantumProfileAdapter.profilePermits(player, terminal, true))
            return false;
        String normalized = TerminalNameRules.normalize(name);
        if (normalized == null || !checkRenameContext(player, window, slot, token, expectedName)) return false;
        if (normalized.isEmpty()) terminal.func_135074_t();
        else terminal.setStackDisplayName(normalized);
        owner.inventory.markDirty();
        // Consume this intent so a delayed/replayed request cannot overwrite a subsequent edit.
        renewRenameToken();
        detectAndSendChanges();
        return true;
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        // Vanilla also calls this when NEI temporarily takes over the screen.
        if (!deferSlotRegistration) onModularContainerClosed();
        super.onContainerClosed(player);
    }

    @Override
    public void onModularContainerClosed() {
        closed = true;
        renameToken = 0;
        super.onModularContainerClosed();
    }

    @Override
    public void onModularContainerOpened() {
        closed = false;
        if (quantum && !owner.worldObj.isRemote) renewRenameToken();
        super.onModularContainerOpened();
    }

    @Override
    public ItemStack slotClick(int slot, int button, int mode, EntityPlayer player) {
        if (!canInteractWith(player) || (mode == 2 && button == heldIndex)) return null;
        if (slot > 0 && slot < inventorySlots.size() && getSlot(slot).getSlotIndex() == heldIndex) return null;
        if (slot != 0) return superSlotClick(slot, button, mode, player);
        if ((mode != 0 && mode != 1) || (button != 0 && button != 1)) return null;
        if (mode == 1) return transferStackInSlot(player, 0);
        supplyCount = currentSupplyCount();
        refreshSupplySlot();
        try {
            return clickSupply(button, player);
        } finally {
            syncSupplyTransaction();
        }
    }

    private ItemStack clickSupply(int button, EntityPlayer player) {
        ItemStack before = getSlot(0).getStack();
        before = before == null ? null : before.copy();
        int count = currentSupplyCount();
        ItemStack cursor = player.inventory.getItemStack();
        if (cursor != null) {
            if (!matchesSupply(cursor)) return before;
            int moved = Math
                .min(Math.min(button == 1 ? 1 : 64, cursor.stackSize), TerminalSupplyStore.CAPACITY - count);
            if (moved <= 0) return before;
            cursor.stackSize -= moved;
            if (cursor.stackSize == 0) player.inventory.setItemStack(null);
            setSupplyCount(count + moved);
        } else if (count > 0 && cursor == null) {
            ItemStack withdrawn = supplyReference == null ? null : supplyReference.copy();
            if (withdrawn == null) return null;
            withdrawn.stackSize = Math.min(count, Math.min(64, withdrawn.getMaxStackSize()));
            if (button == 1) withdrawn.stackSize = 1;
            player.inventory.setItemStack(withdrawn);
            setSupplyCount(count - withdrawn.stackSize);
        }
        player.inventory.markDirty();
        // Vanilla confirms this pre-click slot snapshot; the cursor is already predicted on both sides.
        return before;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slot) {
        if (!canInteractWith(player) || slot < 0 || slot >= inventorySlots.size()) return null;
        try {
            transferSupply(player, slot);
        } finally {
            syncSupplyTransaction();
        }
        // Returning null intentionally avoids vanilla's repeated shift-click dispatch.
        return null;
    }

    private void transferSupply(EntityPlayer player, int slot) {
        int count = currentSupplyCount();
        if (slot == 0) {
            if (player.inventory.getItemStack() == null)
                setSupplyCount(SupplyInventoryTransfer.withdraw(player.inventory, supplyReference, count, heldIndex));
            return;
        }
        Slot source = getSlot(slot);
        ItemStack stack = source.getStack();
        if (!source.canTakeStack(player) || !matchesSupply(stack)) return;
        setSupplyCount(
            SupplyInventoryTransfer
                .deposit(player.inventory, source.getSlotIndex(), supplyReference, count, heldIndex));
    }

    protected void syncSupplyTransaction() {
        if (owner.worldObj.isRemote) return;
        supplyCount = TerminalSupplyStore.count(owner, kind);
        refreshSupplySlot();
        if (owner instanceof EntityPlayerMP player) {
            // Accepted C0E transactions suppress vanilla's S2F replies. Explicitly send both
            // slots and cursor here, before that guard, including unchanged/full inventories.
            player.sendContainerAndContentsToPlayer(this, getInventory());
            player.sendProgressBarUpdate(this, 0, supplyCount);
        }
    }

    private int currentSupplyCount() {
        return owner.worldObj.isRemote ? supplyCount : TerminalSupplyStore.count(owner, kind);
    }

    private void setSupplyCount(int count) {
        supplyCount = Math.max(0, Math.min(TerminalSupplyStore.CAPACITY, count));
        TerminalSupplyStore.set(owner, kind, supplyCount);
        refreshSupplySlot();
    }

    private void refreshSupplySlot() {
        ItemStack stack = supplyReference == null ? null : supplyReference.copy();
        if (stack != null) stack.stackSize = Math.min(supplyCount, Math.min(64, stack.getMaxStackSize()));
        terminalSlots.get(0)
            .putStack(stack == null || stack.stackSize == 0 ? null : stack);
    }

    private boolean matchesSupply(ItemStack stack) {
        return SupplyInventoryTransfer.matches(stack, supplyReference);
    }

    @Override
    public boolean canDragIntoSlot(Slot slot) {
        return slot.slotNumber != 0 && slot.getSlotIndex() != heldIndex;
    }

    @Override
    public boolean func_94530_a(ItemStack stack, Slot slot) {
        return slot.slotNumber != 0 && slot.getSlotIndex() != heldIndex;
    }

    @Override
    public void detectAndSendChanges() {
        if (!owner.worldObj.isRemote) {
            if (quantum && !closed && System.nanoTime() - renameIssuedNanos > RENAME_TTL_NANOS / 2) renewRenameToken();
            supplyCount = TerminalSupplyStore.count(owner, kind);
            refreshSupplySlot();
        }
        super.detectAndSendChanges();
        if (owner.worldObj.isRemote) return;
        values[0] = TerminalSupplyStore.count(owner, kind);
        long tick = owner.worldObj.getTotalWorldTime();
        if (!quantum && (snapshotTick == Long.MIN_VALUE || tick - snapshotTick >= 20 || tick < snapshotTick)) {
            snapshotTick = tick;
            String leader = SpaceProjectManager.getLeader(owner.getUniqueID())
                .toString();
            BigInteger eu = WirelessNetworkManager.getUserEU(owner.getUniqueID());
            String balance = eu == null ? "0"
                : eu.bitLength() > 60 ? new BigDecimal(eu).round(new MathContext(4))
                    .toString() : eu.toString();
            for (int i = 0; i < 36; i++) values[1 + i] = leader.charAt(i);
            for (int i = 0; i < 40; i++) values[37 + i] = i < balance.length() ? balance.charAt(i) : 0;
        }
        for (int i = 0; i < values.length; i++) {
            if (sent[i] == values[i]) continue;
            for (Object listener : crafters) ((ICrafting) listener).sendProgressBarUpdate(this, i, values[i]);
            sent[i] = values[i];
        }
    }

    @Override
    public void addCraftingToCrafters(ICrafting listener) {
        super.addCraftingToCrafters(listener);
        Arrays.fill(sent, -1);
        detectAndSendChanges();
    }

    @Override
    public void updateProgressBar(int id, int value) {
        if (id < 0 || id >= values.length) return;
        values[id] = value;
        if (id == 0) {
            supplyCount = Math.max(0, Math.min(TerminalSupplyStore.CAPACITY, value));
            refreshSupplySlot();
        }
    }

    public String team() {
        return text(1, 36);
    }

    public String balance() {
        return text(37, 40);
    }

    private String text(int start, int length) {
        StringBuilder text = new StringBuilder();
        for (int i = start; i < start + length && values[i] != 0; i++) text.append((char) values[i]);
        return text.toString();
    }
}
