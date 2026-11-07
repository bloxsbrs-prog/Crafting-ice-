package com.example.iceswordrecipe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Addon for ElementalSwords: adds a crafting recipe for the Frost (ice) Sword that can be switched on and off.
 * It asks the ElementalSwords plugin for the real sword item, so the crafted sword is identical to /elementalsword ice.
 */
public final class IceSwordRecipePlugin extends JavaPlugin implements Listener {

    private NamespacedKey recipeKey;
    private NamespacedKey swordTypeKey;   // the tag ElementalSwords puts on every elemental sword
    private boolean recipeActive = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        recipeKey = new NamespacedKey(this, "ice_sword");
        swordTypeKey = NamespacedKey.fromString("elementalswords:sword_type");
        getServer().getPluginManager().registerEvents(this, this);
        applyConfig();
    }

    @Override
    public void onDisable() {
        unregisterRecipe();
    }

    // ---------------------------------------------------------------- recipe

    /** Turns the recipe on or off to match config.yml. */
    private void applyConfig() {
        if (getConfig().getBoolean("recipe.enabled", true)) {
            registerRecipe();
        } else {
            unregisterRecipe();
        }
    }

    /** Asks ElementalSwords for a Frost Sword item (no jar dependency needed, so this plugin builds on its own). */
    private ItemStack createIceSword() {
        Plugin swords = getServer().getPluginManager().getPlugin("ElementalSwords");
        if (swords == null || !swords.isEnabled()) {
            getLogger().warning("ElementalSwords is not installed or not enabled, so the recipe can't be made.");
            return null;
        }
        try {
            for (Method method : swords.getClass().getDeclaredMethods()) {
                if (!method.getName().equals("createSword") || method.getParameterCount() != 1) continue;
                Class<?> typeClass = method.getParameterTypes()[0];
                if (!typeClass.isEnum()) continue;

                Object ice = null;
                for (Object constant : typeClass.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals("ICE")) ice = constant;
                }
                if (ice == null) continue;

                method.setAccessible(true);
                Object result = method.invoke(swords, ice);
                if (result instanceof ItemStack stack) return stack;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().warning("Couldn't get the Frost Sword from ElementalSwords: " + e);
            return null;
        }
        getLogger().warning("Couldn't find the Frost Sword in ElementalSwords. Is it the right version?");
        return null;
    }

    /** Registers (or re-registers) the crafting recipe from config.yml. Returns true if it is now active. */
    private boolean registerRecipe() {
        unregisterRecipe();

        ItemStack result = createIceSword();
        if (result == null) return false;

        List<String> shape = getConfig().getStringList("recipe.shape");
        ConfigurationSection ingredients = getConfig().getConfigurationSection("recipe.ingredients");
        if (shape.isEmpty() || shape.size() > 3 || ingredients == null) {
            getLogger().warning("recipe.shape must have 1 to 3 rows, and recipe.ingredients must be filled in.");
            return false;
        }
        int width = shape.get(0).length();
        for (String row : shape) {
            if (row.length() != width || width < 1 || width > 3) {
                getLogger().warning("Every row in recipe.shape must be the same length, 1 to 3 characters.");
                return false;
            }
        }

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, result);
        recipe.shape(shape.toArray(new String[0]));

        Set<Character> done = new HashSet<>();
        for (String row : shape) {
            for (char c : row.toCharArray()) {
                if (c == ' ' || !done.add(c)) continue;
                String name = ingredients.getString(String.valueOf(c));
                Material material = name == null ? null : Material.matchMaterial(name);
                if (material == null || !material.isItem() || material.isAir()) {
                    getLogger().warning("recipe.ingredients." + c + " is missing or isn't a valid item: " + name);
                    return false;
                }
                recipe.setIngredient(c, material);
            }
        }

        if (!getServer().addRecipe(recipe)) {
            getLogger().warning("The server refused the recipe (is the same recipe already registered?).");
            return false;
        }
        recipeActive = true;

        if (getConfig().getBoolean("recipe.unlock-in-recipe-book", true)) {
            for (Player player : getServer().getOnlinePlayers()) {
                player.discoverRecipe(recipeKey);
            }
        }
        getLogger().info("Frost Sword recipe is enabled.");
        return true;
    }

    private void unregisterRecipe() {
        if (recipeKey != null) {
            getServer().removeRecipe(recipeKey);
            for (Player player : getServer().getOnlinePlayers()) {
                player.undiscoverRecipe(recipeKey);
            }
        }
        recipeActive = false;
    }

    // ---------------------------------------------------------------- events

    /** Stops people using an existing elemental sword as the "sword" ingredient. */
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof Keyed keyed) || !keyed.getKey().equals(recipeKey)) return;

        for (HumanEntity viewer : event.getViewers()) {
            if (!viewer.hasPermission("iceswordrecipe.craft")) {
                event.getInventory().setResult(null);
                return;
            }
        }
        if (swordTypeKey == null) return;
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (item == null || !item.hasItemMeta()) continue;
            if (item.getItemMeta().getPersistentDataContainer().has(swordTypeKey, PersistentDataType.STRING)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (recipeActive && getConfig().getBoolean("recipe.unlock-in-recipe-book", true)) {
            event.getPlayer().discoverRecipe(recipeKey);
        }
    }

    // ---------------------------------------------------------------- recipe editor menu

    private static final int MENU_SIZE = 45;
    private static final int[] GRID_SLOTS = {11, 12, 13, 20, 21, 22, 29, 30, 31};
    private static final int ARROW_SLOT = 23;
    private static final int RESULT_SLOT = 24;
    private static final int TOGGLE_SLOT = 37;
    private static final int SAVE_SLOT = 39;
    private static final int CLEAR_SLOT = 41;
    private static final int RESET_SLOT = 43;

    private static final Material[] DEFAULT_GRID = {
            Material.BLUE_ICE, Material.DIAMOND, Material.BLUE_ICE,
            Material.DIAMOND, Material.DIAMOND_SWORD, Material.DIAMOND,
            Material.BLUE_ICE, Material.NETHERITE_INGOT, Material.BLUE_ICE};

    /** Marks our editor window so clicks in it can be recognised. */
    private static final class RecipeMenu implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private ItemStack button(Material material, String name, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
        });
        return item;
    }

    private ItemStack toggleButton() {
        boolean on = getConfig().getBoolean("recipe.enabled", true);
        return on
                ? button(Material.LIME_CONCRETE, "Recipe: ENABLED", NamedTextColor.GREEN, "Click to disable the recipe")
                : button(Material.RED_CONCRETE, "Recipe: DISABLED", NamedTextColor.RED, "Click to enable the recipe");
    }

    /** Reads the current recipe from config.yml into a 3x3 array (top-left aligned, null = empty). */
    private Material[] gridFromConfig() {
        Material[] grid = new Material[9];
        List<String> shape = getConfig().getStringList("recipe.shape");
        ConfigurationSection ingredients = getConfig().getConfigurationSection("recipe.ingredients");
        if (ingredients == null) return grid;
        for (int r = 0; r < shape.size() && r < 3; r++) {
            String row = shape.get(r);
            for (int c = 0; c < row.length() && c < 3; c++) {
                char ch = row.charAt(c);
                if (ch == ' ') continue;
                String name = ingredients.getString(String.valueOf(ch));
                Material material = name == null ? null : Material.matchMaterial(name);
                if (material != null && material.isItem() && !material.isAir()) grid[r * 3 + c] = material;
            }
        }
        return grid;
    }

    private void setGrid(Inventory inv, Material[] grid) {
        for (int i = 0; i < 9; i++) {
            inv.setItem(GRID_SLOTS[i], grid[i] == null ? null : new ItemStack(grid[i]));
        }
    }

    private void renderMenu(Inventory inv, Material[] grid) {
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int i = 0; i < MENU_SIZE; i++) inv.setItem(i, filler);

        setGrid(inv, grid);
        inv.setItem(ARROW_SLOT, button(Material.ARROW, "Makes", NamedTextColor.WHITE));

        ItemStack result = createIceSword();
        inv.setItem(RESULT_SLOT, result != null ? result
                : button(Material.BARRIER, "ElementalSwords not found", NamedTextColor.RED));

        inv.setItem(TOGGLE_SLOT, toggleButton());
        inv.setItem(SAVE_SLOT, button(Material.EMERALD_BLOCK, "Save recipe", NamedTextColor.GREEN,
                "Saves the grid and applies it"));
        inv.setItem(CLEAR_SLOT, button(Material.BARRIER, "Clear grid", NamedTextColor.RED,
                "Empties all 9 slots"));
        inv.setItem(RESET_SLOT, button(Material.CRAFTING_TABLE, "Reset to default", NamedTextColor.YELLOW,
                "Puts the original recipe back in the grid"));
    }

    private void openMenu(Player player) {
        RecipeMenu holder = new RecipeMenu();
        Inventory inv = getServer().createInventory(holder, MENU_SIZE, Component.text("Ice Sword Recipe Editor"));
        holder.inventory = inv;
        renderMenu(inv, gridFromConfig());
        player.openInventory(inv);
        send(player, "Pick up an item, click a grid slot to put it there, click a filled slot to empty it, then press Save.",
                NamedTextColor.AQUA);
    }

    /** Writes the grid in the menu to config.yml. Returns false if the grid is empty. */
    private boolean saveGrid(Inventory inv, Player player) {
        Material[] grid = new Material[9];
        int minR = 3, maxR = -1, minC = 3, maxC = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.getItem(GRID_SLOTS[i]);
            if (item == null || item.getType().isAir()) continue;
            grid[i] = item.getType();
            int r = i / 3, c = i % 3;
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
            minC = Math.min(minC, c);
            maxC = Math.max(maxC, c);
        }
        if (maxR < 0) {
            send(player, "The grid is empty. Put at least one item in it.", NamedTextColor.RED);
            return false;
        }

        // Cut the grid down to the smallest box that holds all the items, and give each item type a letter
        Map<Material, Character> letters = new LinkedHashMap<>();
        List<String> rows = new ArrayList<>();
        for (int r = minR; r <= maxR; r++) {
            StringBuilder row = new StringBuilder();
            for (int c = minC; c <= maxC; c++) {
                Material material = grid[r * 3 + c];
                if (material == null) {
                    row.append(' ');
                    continue;
                }
                Character letter = letters.get(material);
                if (letter == null) {
                    letter = (char) ('A' + letters.size());
                    letters.put(material, letter);
                }
                row.append(letter.charValue());
            }
            rows.add(row.toString());
        }

        getConfig().set("recipe.shape", rows);
        getConfig().set("recipe.ingredients", null);
        for (Map.Entry<Material, Character> entry : letters.entrySet()) {
            getConfig().set("recipe.ingredients." + entry.getValue(), entry.getKey().name());
        }
        saveConfig();
        return true;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        try {
            handleMenuClick(event);
        } catch (Throwable t) {
            event.setCancelled(true);
            reportError(event.getWhoClicked(), "using the recipe menu", t);
        }
    }

    private void handleMenuClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof RecipeMenu)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= MENU_SIZE) {
            // Your own inventory works normally, but nothing may be moved into the menu
            if (event.isShiftClick() || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
            }
            return;
        }

        event.setCancelled(true);   // nothing is ever taken out of the menu
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.hasPermission("iceswordrecipe.admin")) return;

        // Grid slots: put the item on your cursor in the slot (your item isn't used up), or empty it
        for (int i = 0; i < 9; i++) {
            if (GRID_SLOTS[i] != slot) continue;
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                if (cursor.getType().isItem()) {
                    top.setItem(slot, new ItemStack(cursor.getType(), 1));
                }
            } else {
                top.setItem(slot, null);
            }
            return;
        }

        if (slot == TOGGLE_SLOT) {
            boolean nowEnabled = !getConfig().getBoolean("recipe.enabled", true);
            getConfig().set("recipe.enabled", nowEnabled);
            saveConfig();
            if (nowEnabled) {
                if (!registerRecipe()) send(player, "Couldn't enable the recipe. Check the server console.", NamedTextColor.RED);
            } else {
                unregisterRecipe();
            }
            top.setItem(TOGGLE_SLOT, toggleButton());
        } else if (slot == SAVE_SLOT) {
            if (!saveGrid(top, player)) return;
            if (getConfig().getBoolean("recipe.enabled", true)) {
                if (registerRecipe()) {
                    send(player, "Recipe saved and active.", NamedTextColor.GREEN);
                } else {
                    send(player, "Recipe saved, but it couldn't be activated. Check the server console.", NamedTextColor.RED);
                }
            } else {
                send(player, "Recipe saved. It's currently disabled, so click the red button to turn it on.", NamedTextColor.YELLOW);
            }
        } else if (slot == CLEAR_SLOT) {
            setGrid(top, new Material[9]);
        } else if (slot == RESET_SLOT) {
            setGrid(top, DEFAULT_GRID);
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RecipeMenu)) return;
        for (int raw : event.getRawSlots()) {
            if (raw < MENU_SIZE) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- command

    private static void send(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text(text, color));
    }

    /** Shows what actually went wrong, in chat and in the console, instead of Minecraft's vague "unexpected error". */
    private void reportError(CommandSender sender, String what, Throwable t) {
        getLogger().log(java.util.logging.Level.SEVERE, "Error while " + what, t);
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String where = root.getStackTrace().length > 0 ? root.getStackTrace()[0].toString() : "unknown";
        send(sender, "IceSwordRecipe error while " + what + ":", NamedTextColor.RED);
        send(sender, root.getClass().getSimpleName() + ": " + root.getMessage(), NamedTextColor.RED);
        send(sender, "at " + where, NamedTextColor.GRAY);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        try {
            runCommand(sender, sub);
        } catch (Throwable t) {
            reportError(sender, "running /icerecipe " + sub, t);
        }
        return true;
    }

    private void runCommand(CommandSender sender, String sub) {
        switch (sub) {
            case "enable" -> {
                getConfig().set("recipe.enabled", true);
                saveConfig();
                if (registerRecipe()) {
                    send(sender, "Ice Sword recipe enabled.", NamedTextColor.GREEN);
                } else {
                    send(sender, "Couldn't enable the recipe. Check the server console for the reason.", NamedTextColor.RED);
                }
            }
            case "disable" -> {
                getConfig().set("recipe.enabled", false);
                saveConfig();
                unregisterRecipe();
                send(sender, "Ice Sword recipe disabled.", NamedTextColor.YELLOW);
            }
            case "reload" -> {
                reloadConfig();
                applyConfig();
                send(sender, "Config reloaded. Recipe is " + (recipeActive ? "enabled." : "disabled."),
                        recipeActive ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
            }
            case "menu", "edit" -> {
                if (sender instanceof Player player) {
                    openMenu(player);
                } else {
                    send(sender, "The menu can only be opened in the game.", NamedTextColor.RED);
                }
            }
            case "status" -> {
                boolean setting = getConfig().getBoolean("recipe.enabled", true);
                send(sender, "Ice Sword recipe setting: " + (setting ? "enabled" : "disabled")
                        + " | active right now: " + (recipeActive ? "yes" : "no"), NamedTextColor.AQUA);
            }
            default -> send(sender, "Usage: /icerecipe <menu|enable|disable|status|reload>", NamedTextColor.RED);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("menu", "enable", "disable", "status", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) options.add(s);
            }
        }
        return options;
    }
}
