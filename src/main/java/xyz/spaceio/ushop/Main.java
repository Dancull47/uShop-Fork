package xyz.spaceio.ushop;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import net.kyori.adventure.text.Component;
import org.apache.commons.lang.WordUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.md_5.bungee.api.ChatColor;
import net.milkbowl.vault.economy.Economy;
import xyz.spaceio.customitem.CustomItem;

public class Main extends JavaPlugin {

    public static final String NULL = null;
    /*
     * Vault Economy plugin
     */
    private Economy economy = null;

    /*
     * Main config.yml
     */
    private FileConfiguration cfg;

    /*
     * Inventories that are currently open
     */
    private Map<Player, Inventory> openShops = new HashMap<Player, Inventory>();

    /*
     * List that contains all information about sell items
     */
    private List<CustomItem> customItems = new ArrayList<CustomItem>();

    /*
     * Gson object for serializing processes
     */
    private Gson gson = new Gson();


    /**
     * Logger for logging all sell actions
     */
    private PrintStream logs;


    /**
     * The plugin's main task for updating GUI elements
     */
    private BukkitTask pluginTask;

    @Override
    public void onEnable() {
        setupEconomy();

        this.saveDefaultConfig();
        this.loadItems();

        // registering command
        registerCommand(this.cfg.getString("command"));

        this.getCommand("ushop").setExecutor(new uShopCmd(this));

        this.getServer().getPluginManager().registerEvents(new Listeners(this), this);

        String fileName = new SimpleDateFormat("yyyy'-'MM'-'dd'_'HH'-'mm'-'ss'_'zzz'.log'").format(new Date());
        File dir = new File(getDataFolder(), "logs");
        dir.mkdirs();
        File logs = new File(dir, fileName);
        try {
            this.logs = new PrintStream(logs);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        // async update task
        pluginTask = this.getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            synchronized (openShops) {
                Iterator<Player> it = openShops.keySet().iterator();
                while (it.hasNext()) {
                    Player p;
                    try {
                        p = it.next();
                    } catch (ConcurrentModificationException ex) {
                        // Triggered in some rare cases, ignore it
                        continue;
                    }
                    if (p.getOpenInventory().getTopInventory() != null) {
                        Inventory shopInventory = p.getOpenInventory().getTopInventory();

                        if (this.getOpenShops().values().contains(shopInventory)) {
                            // Update
                            ItemStack[] invContent = shopInventory.getContents();
                            invContent[shopInventory.getSize() - 5] = null;

                            List<String> lore = new ArrayList<String>();
                            double[] totalPrice = {0d};

                            getSalableItems(invContent).forEach((item, amount) -> {
                                double totalStackPrice = item.getPrice() * amount;
                                totalPrice[0] += totalStackPrice;
                                lore.addAll(getCustomItemDescription(item, amount));
                            });

                            ItemStack sell = shopInventory.getItem(shopInventory.getSize() - 5);

                            if (sell == null)
                                continue;
                            if (sell.getItemMeta() == null)
                                continue;

                            ItemMeta im = sell.getItemMeta();
                            im.setDisplayName(ChatColor.GREEN + "Sell");

                            List<String> lore2 = new ArrayList<>();
                            lore2.add("");
                            lore2.add(ChatColor.DARK_GRAY.toString() + ChatColor.BOLD + "» " + ChatColor.YELLOW + "Click to Sell for " + org.bukkit.ChatColor.YELLOW + "⛂ " +  ChatColor.GOLD + numberFormat(totalPrice[0]));

                            im.setLore(lore2);
                            sell.setItemMeta(im);

                            shopInventory.setItem(shopInventory.getSize() - 5, sell);

                        } else {
                            ItemStack[] stacks = openShops.get(p).getContents();
                            stacks[openShops.get(p).getSize() - 5] = null;
                            for (int i = 0; i < stacks.length; i++) {
                                if (stacks[i] != null && NBTUtils.getInt(stacks[i], "menuItem") == 1) {
                                    stacks[i] = null;
                                }
                            }
                            addToInv(p.getInventory(), stacks);
                            it.remove();
                        }


                    }
                }
            }
        }, 20L, 20L);

    }

    @Override
    public void onDisable() {
        logs.flush();
        logs.close();

        pluginTask.cancel();
    }

    /**
     * @return the logs
     */
    public PrintStream getLogs() {
        return logs;
    }

    public List<String> getCustomItemDescription(CustomItem item, int amount) {
        return getCustomItemDescription(item, amount, cfg.getString("gui-item-enumeration-format").replace("&", "§"));
    }

    public List<String> getCustomItemDescription(CustomItem item, int amount, String itemEnumFormat) {
        List<String> list = new ArrayList<String>();

        String s = itemEnumFormat.replace("%amount%", amount + "")
                .replace("%material%", item.getDisplayname() == NULL ? WordUtils.capitalize(item.getMaterial().toLowerCase().replace("_", " ")) : item.getDisplayname())
                .replace("%price%", numberFormat(item.getPrice() * amount));
        list.add(s);

        // adding enchantements
        item.getEnchantements().forEach((enchantement, level) -> {
            list.add(String.format("§7%s %s", WordUtils.capitalize(enchantement), Utils.toRoman(level)));
        });

        item.getFlags().forEach(flag -> {
            list.add(String.format("§e%s", flag.name().toLowerCase()));
        });

        return list;
    }

    /**
     * Saved all Custom items to the config.
     */
    public void saveMainConfig() {
        List<CustomItem> advancedItems = new ArrayList<CustomItem>();
        List<String> simpleItems = new ArrayList<String>();

        for (CustomItem customItem : customItems) {
            if (customItem.isSimpleItem()) {
                simpleItems.add(customItem.getMaterial() + ":" + customItem.getPrice());
            } else {
                advancedItems.add(customItem);
            }
        }
        cfg.set("sell-prices-simple", simpleItems);
        cfg.set("sell-prices", gson.toJson(advancedItems));
        this.saveConfig();
    }

    public void addToInv(Inventory inv, ItemStack[] is) {
        for (ItemStack stack : is) {
            if (stack != null) {

                inv.addItem(stack);
            }
        }
    }

    public HashMap<CustomItem, Integer> getSalableItems(ItemStack[] is) {
        HashMap<CustomItem, Integer> customItemsMap = new HashMap<CustomItem, Integer>();
        for (ItemStack stack : is) {
            if (stack != null) {
                if (stack.getType().toString().toUpperCase().contains("SHULKER_BOX")) {
                    Inventory container = ((InventoryHolder) ((BlockStateMeta) stack.getItemMeta()).getBlockState()).getInventory();
                    for (int j = 0; j < container.getSize(); j++) {
                        ItemStack shulkerItem = container.getItem(j);
                        if (shulkerItem != null && !shulkerItem.getType().equals(Material.AIR)) {
                            Optional<CustomItem> opt = findCustomItem(shulkerItem);
                            if (opt.isPresent() && this.isSalable(shulkerItem)) {
                                // add item to map
                                customItemsMap.compute(opt.get(), (k, v) -> v == null ? shulkerItem.getAmount() : v + shulkerItem.getAmount());
                            }
                        }
                    }
                } else {
                    // check if item is in the custom item list
                    Optional<CustomItem> opt = findCustomItem(stack);
                    if (opt.isPresent() && this.isSalable(stack)) {
                        // add item to map
                        customItemsMap.compute(opt.get(), (k, v) -> v == null ? stack.getAmount() : v + stack.getAmount());
                    }
                }
            }
        }
        return customItemsMap;
    }

    /**
     * Finds the representing Custom Item for a certain Item Stack
     *
     * @param stack
     * @return
     */
    public Optional<CustomItem> findCustomItem(ItemStack stack) {
        return customItems.stream().filter((item) -> item.matches(stack)).findFirst();
    }

    public double calcWorthOfContent(ItemStack[] content) {
        HashMap<CustomItem, Integer> salable = getSalableItems(content);
        return salable.keySet().stream().mapToDouble(v -> v.getPrice() * salable.get(v)).sum();
    }

    public boolean isSalable(ItemStack is) {
        if (is == null || is.getType() == null || is.getType() == Material.AIR) return false;
        Optional<CustomItem> customItemOptional = this.findCustomItem(is);
        if (customItemOptional.isPresent()) {
            if (customItemOptional.get().getPrice() > 0d) {
                return true;
            }
        }
        return false;
    }

    public Economy getEconomy() {
        return economy;
    }

    public Map<Player, Inventory> getOpenShops() {
        return openShops;
    }

    public List<CustomItem> getCustomItems() {
        return customItems;
    }

    public void registerCommand(String cmdLabel) {

        try {
            final Field bukkitCommandMap = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            // remove old command if already used
            SimplePluginManager spm = (SimplePluginManager) this.getServer().getPluginManager();
            Field f = SimplePluginManager.class.getDeclaredField("commandMap");
            f.setAccessible(true);
            SimpleCommandMap scm = (SimpleCommandMap) f.get(spm);

            Field f2 = SimpleCommandMap.class.getDeclaredField("knownCommands");
            f2.setAccessible(true);
            HashMap<String, Command> map = (HashMap<String, Command>) f2.get(scm);
            map.remove(cmdLabel);

            f.setAccessible(false);

            // register
            bukkitCommandMap.setAccessible(true);
            CommandMap commandMap = (CommandMap) bukkitCommandMap.get(Bukkit.getServer());
            Cmd cmd = new Cmd(this, cmdLabel);
            commandMap.register(cmdLabel, cmd);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    boolean setupEconomy() {
        RegisteredServiceProvider<Economy> economyProvider = getServer().getServicesManager()
                .getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (economyProvider != null) {
            economy = economyProvider.getProvider();
        }
        return (economy != null);
    }

    public void addCustomItem(CustomItem i) {
        customItems.add(i);
    }

    public boolean isShopGUI(InventoryView inventoryView) {
        return inventoryView.getTitle().equals(org.bukkit.ChatColor.WHITE.toString() + org.bukkit.ChatColor.BOLD + "»" +
                org.bukkit.ChatColor.GRAY + org.bukkit.ChatColor.BOLD + "» " +
                org.bukkit.ChatColor.DARK_GRAY + "Sell " +
                org.bukkit.ChatColor.GRAY + org.bukkit.ChatColor.BOLD + " «" +
                org.bukkit.ChatColor.WHITE + org.bukkit.ChatColor.BOLD + "«");
    }

    public void openShop(Player p) {
        Inventory inv = Bukkit.createInventory(null, 9 * this.getConfig().getInt("gui-rows"),
                org.bukkit.ChatColor.WHITE.toString() + org.bukkit.ChatColor.BOLD + "»" +
                        org.bukkit.ChatColor.GRAY + org.bukkit.ChatColor.BOLD + "» " +
                        org.bukkit.ChatColor.DARK_GRAY + "Sell " +
                        org.bukkit.ChatColor.GRAY + org.bukkit.ChatColor.BOLD + " «" +
                        org.bukkit.ChatColor.WHITE + org.bukkit.ChatColor.BOLD + "«");
        ItemStack is = new ItemStack(Material.CAULDRON);
        ItemMeta im = is.getItemMeta();
        im.setDisplayName(ChatColor.GREEN + "Sell");
        is.setItemMeta(im);
        inv.setItem(inv.getSize() - 5, is);

        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(" ");
        pane.setItemMeta(meta);
        pane = NBTUtils.set(pane, 1, "menuItem");
        for (int i = inv.getSize() - 9; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null || inv.getItem(i).getType().equals(Material.AIR)) {
                inv.setItem(i, pane.clone());
            }
        }

        p.openInventory(inv);
        this.getOpenShops().put(p, inv);


    }

    /**
     * Loads all item configurations from the config.yml
     */
    private void loadItems() {
        this.cfg = this.getConfig();

        if (this.cfg.getString("sell-prices") != null) {
            customItems = gson.fromJson(cfg.getString("sell-prices"), new TypeToken<List<CustomItem>>() {
            }.getType());
        }

        // converting simple items to custom items
        if (this.cfg.contains("sell-prices-simple")) {
            for (String entry : this.cfg.getStringList("sell-prices-simple")) {
                try {
                    CustomItem ci = new CustomItem(new ItemStack(Material.valueOf(entry.split(":")[0])), Double.parseDouble(entry.split(":")[1]));
                    customItems.add(ci);
                } catch (Exception e) {
                    System.out.println("Error in config.yml: " + entry);
                }
            }
        } else {
            // adding default materials
            List<String> entries = Arrays.stream(Material.values()).map(v -> v.name() + ":0.0").collect(Collectors.toList());
            this.cfg.set("sell-prices-simple", entries);
            this.saveConfig();
            for (Material mat : Material.values()) {
                customItems.add(new CustomItem(new ItemStack(mat), 0d));
            }
        }
    }

    /**
     * @return amount of configured custom items
     */
    public long getCustomItemCount() {
        return customItems.stream().filter(p -> !p.isSimpleItem()).count();
    }

    public void reloadItems() {
        this.reloadConfig();
        loadItems();

    }


    public String numberFormat(int number) {
        return shortened(number);
    }

    public String numberFormat(double number) {
        return shortened((long) number);
    }

    public String numberFormat(long number) {
        return shortened(number);
    }

    public String shortened(long number) {
        if (number > 999_999) {
            String one = String.valueOf(number).charAt(0) + "." + String.valueOf(number).charAt(1) + String.valueOf(number).charAt(2);
            String two = String.valueOf(number).charAt(0) + "" + String.valueOf(number).charAt(1) + "." + String.valueOf(number).charAt(2);
            String three = String.valueOf(number).charAt(0) + "" + String.valueOf(number).charAt(1) + String.valueOf(number).charAt(2) + "." + String.valueOf(number).charAt(3);

            if (number >= 1_000_000_000_000_000_000L)
                return one + "QT";

            else if (number >= 100_000_000_000_000_000L)
                return three + "QD";
            else if (number >= 10_000_000_000_000_000L)
                return two + "QD";
            else if (number >= 1_000_000_000_000_000L)
                return one + "QD";

            else if (number >= 100_000_000_000_000L)
                return three + "T";
            else if (number >= 10_000_000_000_000L)
                return two + "T";
            else if (number >= 1_000_000_000_000L)
                return one + "T";

            else if (number >= 100_000_000_000L)
                return three + "B";
            else if (number >= 10_000_000_000L)
                return two + "B";
            else if (number >= 1_000_000_000)
                return one + "B";

            else if (number >= 100_000_000)
                return three + "M";
            else if (number >= 10_000_000)
                return two + "M";
            else if (number >= 1_000_000)
                return one + "M";

            else
                return commas(number);
        }
        return commas(number);
    }

    public String commas(long number) {
        DecimalFormat format = new DecimalFormat("###,###,###");
        return format.format(number);
    }

}
