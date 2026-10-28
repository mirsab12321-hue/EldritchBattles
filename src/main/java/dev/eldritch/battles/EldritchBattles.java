package dev.eldritch.battles;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** First public test build. No dependence on EpicDuels or Custom Teams. */
@SuppressWarnings("deprecation")
public final class EldritchBattles extends JavaPlugin implements Listener {
    private final Map<UUID, Party> membership = new HashMap<>();
    private final Map<UUID, Match> playing = new HashMap<>();
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<String, Kit> kits = new LinkedHashMap<>();
    private final Map<UUID, UUID> invitations = new HashMap<>();
    private File arenaFile, kitsFile, recoveryFile;
    private YamlConfiguration arenaYaml, kitsYaml, recoveryYaml;
    private final String prefix = ChatColor.GOLD + "[Eldritch] " + ChatColor.RESET;

    static class Party {
        final UUID owner;
        final LinkedHashMap<UUID, Integer> teams = new LinkedHashMap<>();
        boolean ffa = false, friendlyFire = false, drops = false, border = true;
        int bestOf = 3;
        String kit, arena;
        Match match;
        Party(UUID owner) { this.owner = owner; teams.put(owner, 1); }
    }
    static class Kit {
        ItemStack[] storage, armor;
        ItemStack offhand;
        Kit(ItemStack[] storage, ItemStack[] armor, ItemStack offhand) {
            this.storage = cloneItems(storage); this.armor = cloneItems(armor);
            this.offhand = offhand == null ? null : offhand.clone();
        }
    }
    static class Arena {
        final String id, worldName;
        Location first, second;
        final List<Location> spawns = new ArrayList<>();
        List<BlockRecord> baseline;
        boolean busy;
        Arena(String id, String worldName) { this.id=id; this.worldName=worldName; }
        World world() { return Bukkit.getWorld(worldName); }
        boolean ready() { return first != null && second != null && spawns.size() >= 2 && baseline != null; }
        int minX(){return Math.min(first.getBlockX(), second.getBlockX());}
        int maxX(){return Math.max(first.getBlockX(), second.getBlockX());}
        int minY(){return Math.min(first.getBlockY(), second.getBlockY());}
        int maxY(){return Math.max(first.getBlockY(), second.getBlockY());}
        int minZ(){return Math.min(first.getBlockZ(), second.getBlockZ());}
        int maxZ(){return Math.max(first.getBlockZ(), second.getBlockZ());}
        long volume(){return (long)(maxX()-minX()+1)*(maxY()-minY()+1)*(maxZ()-minZ()+1);}
        boolean inside(Location l) {return l!=null && l.getWorld()!=null && l.getWorld().getName().equals(worldName)
            && l.getBlockX()>=minX() && l.getBlockX()<=maxX() && l.getBlockY()>=minY() && l.getBlockY()<=maxY()
            && l.getBlockZ()>=minZ() && l.getBlockZ()<=maxZ();}
    }
    static class BlockRecord {
        final int x,y,z;
        final String data;
        final ItemStack[] inventory;
        BlockRecord(int x,int y,int z,String data,ItemStack[] inventory){this.x=x;this.y=y;this.z=z;this.data=data;this.inventory=inventory;}
    }
    static class PlayerBackup {
        final UUID uuid;
        final Location location;
        final GameMode gamemode;
        final Kit inventory;
        final int food;
        PlayerBackup(Player p){
            uuid=p.getUniqueId(); location=p.getLocation().clone(); gamemode=p.getGameMode();
            inventory=new Kit(p.getInventory().getStorageContents(),p.getInventory().getArmorContents(),p.getInventory().getItemInOffHand());
            food=p.getFoodLevel();
        }
    }
    static class Match {
        final Party party; final Arena arena; final Kit kit;
        final Set<UUID> eliminated = new HashSet<>();
        final Map<String,Integer> wins=new HashMap<>();
        final Map<UUID,PlayerBackup> backups=new HashMap<>();
        final List<BukkitTask> tasks = new ArrayList<>();
        int round=0; boolean ending=false, transitioning=true;
        Match(Party p,Arena a,Kit k){party=p;arena=a;kit=k;}
        String side(UUID u) {return party.ffa ? u.toString() : "team" + party.teams.getOrDefault(u,0);}
    }
    static class Menu implements InventoryHolder {
        final String page;
        final Party party;
        final Map<Integer,String> actions=new HashMap<>();
        Inventory inventory;
        Menu(String page, Party party){this.page=page;this.party=party;}
        public Inventory getInventory(){return inventory;}
    }
    static ItemStack[] cloneItems(ItemStack[] src) {
        if(src==null) return new ItemStack[0];
        ItemStack[] ret=new ItemStack[src.length];
        for(int i=0;i<src.length;i++)ret[i]=src[i]==null?null:src[i].clone();
        return ret;
    }
    @Override public void onEnable(){
        saveDefaultConfig(); getDataFolder().mkdirs();
        arenaFile=new File(getDataFolder(),"arenas.yml");
        kitsFile=new File(getDataFolder(),"kits.yml");
        recoveryFile=new File(getDataFolder(),"recover-inventory.yml");
        arenaYaml=YamlConfiguration.loadConfiguration(arenaFile);
        kitsYaml=YamlConfiguration.loadConfiguration(kitsFile);
        recoveryYaml=YamlConfiguration.loadConfiguration(recoveryFile);
        loadKits(); loadArenas();
        getServer().getPluginManager().registerEvents(this,this);
        getLogger().info("Eldritch Battles v0.1.0 enabled. Use /eb help");
    }
    @Override public void onDisable(){
        for(Match match: new HashSet<>(playing.values())){
            for(PlayerBackup b: match.backups.values()){
                Player p=Bukkit.getPlayer(b.uuid);
                if(p!=null && !p.isDead()) restore(p,b);
            }
            for(BukkitTask t:match.tasks)t.cancel();
            match.arena.busy=false;
        }
        saveArenas();
    }
    private void msg(CommandSender p,String s){p.sendMessage(prefix+ChatColor.translateAlternateColorCodes('&',s));}
    private void partyMsg(Party party,String s){for(UUID u:party.teams.keySet()){
        Player player=Bukkit.getPlayer(u);if(player!=null)msg(player,s);
    }}
    private boolean admin(Player p){if(p.hasPermission("eldritch.admin"))return true;msg(p,"&cYou need OP/admin permission.");return false;}
    private void help(Player p){
        msg(p,"&e/eb party create | invite <player> | join <host> | leave | list | menu");
        msg(p,"&e/eb kit save <name> | list | delete <name> &7(admin)");
        msg(p,"&e/eb arena create <name> | edit <name> | pos1 <name> | pos2 <name>");
        msg(p,"&e/eb arena spawn <name> <1-16> | save <name> | list &7(admin)");
        msg(p,"&e/eb menu &7(open your team/kit/settings UI)");
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof Player p)){msg(sender,"Players must use this command in game.");return true;}
        if(args.length==0 || args[0].equalsIgnoreCase("help")){help(p);return true;}
        try {
            switch(args[0].toLowerCase(Locale.ROOT)) {
                case "menu" -> openMenu(p,"teams");
                case "party" -> partyCommand(p,Arrays.copyOfRange(args,1,args.length));
                case "kit" -> kitCommand(p,Arrays.copyOfRange(args,1,args.length));
                case "arena" -> arenaCommand(p,Arrays.copyOfRange(args,1,args.length));
                default -> help(p);
            }
        } catch(Exception ex){
            msg(p,"&cCommand failed: "+ex.getMessage());
            getLogger().warning("Command error for "+p.getName()+": "+ex);
        }
        return true;
    }
    private void partyCommand(Player p,String[] args){
        if(args.length==0){openMenu(p,"teams");return;}
        Party party=membership.get(p.getUniqueId());
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "create" -> {
                if(party!=null){msg(p,"&cLeave your existing party first.");return;}
                Party created=new Party(p.getUniqueId()); membership.put(p.getUniqueId(),created);
                msg(p,"&aParty created! /eb party invite <player>");
            }
            case "invite" -> {
                if(!isLeader(p,party)||args.length<2){msg(p,"&cUsage: /eb party invite <player> (leader only)");return;}
                Player target=Bukkit.getPlayerExact(args[1]);
                if(target==null||membership.containsKey(target.getUniqueId())){msg(p,"&cPlayer offline or already in a party.");return;}
                invitations.put(target.getUniqueId(),party.owner);
                msg(target,"&6"+p.getName()+" invited you! &a/eb party join "+p.getName());
                msg(p,"&aInvite sent to "+target.getName());
            }
            case "join" -> {
                if(party!=null||args.length<2){msg(p,"&cUsage: /eb party join <host>; leave current party first.");return;}
                Player host=Bukkit.getPlayerExact(args[1]);
                if(host==null || !host.getUniqueId().equals(invitations.get(p.getUniqueId()))){msg(p,"&cNo valid invitation from that host.");return;}
                Party joined=membership.get(host.getUniqueId());
                if(joined==null||!joined.owner.equals(host.getUniqueId())||joined.match!=null){msg(p,"&cParty unavailable.");return;}
                if(joined.teams.size()>=16){msg(p,"&cParty is full.");return;}
                joined.teams.put(p.getUniqueId(),2);membership.put(p.getUniqueId(),joined);
                invitations.remove(p.getUniqueId());partyMsg(joined,"&a"+p.getName()+" joined the party!");
            }
            case "leave" -> {
                if(party==null){msg(p,"&cNot in a party.");return;}
                if(party.match!=null){msg(p,"&cCan't leave during an active match.");return;}
                if(party.owner.equals(p.getUniqueId())){
                    for(UUID u:party.teams.keySet())membership.remove(u);
                    partyMsg(party,"&cParty disbanded.");
                } else {party.teams.remove(p.getUniqueId());membership.remove(p.getUniqueId());partyMsg(party,"&e"+p.getName()+" left.");}
            }
            case "list" -> {
                if(party==null){msg(p,"&cNot in a party.");return;}
                msg(p,"&6Party members: "+party.teams.size());
                for(Map.Entry<UUID,Integer> e:party.teams.entrySet()){
                    OfflinePlayer op=Bukkit.getOfflinePlayer(e.getKey());
                    msg(p,"&7- "+op.getName()+" : "+(party.ffa?"FFA": "Team "+e.getValue()));
                }
            }
            case "menu" -> openMenu(p,"teams");
            default -> msg(p,"&c/eb party create|invite|join|leave|list|menu");
        }
    }
    private boolean isLeader(Player p,Party party){return party!=null&&party.owner.equals(p.getUniqueId());}
    private void kitCommand(Player p,String[] a){
        if(a.length==0){msg(p,"&e/eb kit save <name> | list | delete <name>");return;}
        if(a[0].equalsIgnoreCase("list")){msg(p,"&eSaved kits: "+String.join(", ",kits.keySet()));return;}
        if(!admin(p))return;
        if(a.length<2){msg(p,"&cSpecify kit name, e.g. /eb kit save sword");return;}
        String id=validId(a[1]);if(id==null){msg(p,"&cUse letters, numbers, underscore or dash.");return;}
        if(a[0].equalsIgnoreCase("save")){
            PlayerInventory inv=p.getInventory();
            kits.put(id,new Kit(inv.getStorageContents(),inv.getArmorContents(),inv.getItemInOffHand()));
            saveKits();msg(p,"&aSaved kit '&f"+id+"&a' including empty slots, armour and offhand.");
        }else if(a[0].equalsIgnoreCase("delete")){
            kits.remove(id);saveKits();msg(p,"&eKit deleted.");
        }else msg(p,"&cUnknown kit command.");
    }
    private String validId(String id){id=id.toLowerCase(Locale.ROOT);return id.matches("[a-z0-9_-]{1,24}")?id:null;}
    private ChunkGenerator voidGenerator(){
        return new ChunkGenerator(){
            @Override public boolean shouldGenerateNoise(){return false;}
            @Override public boolean shouldGenerateSurface(){return false;}
            @Override public boolean shouldGenerateCaves(){return false;}
            @Override public boolean shouldGenerateDecorations(){return false;}
            @Override public boolean shouldGenerateMobs(){return false;}
            @Override public boolean shouldGenerateStructures(){return false;}
        };
    }
    private World loadWorld(String worldName){
        World existing=Bukkit.getWorld(worldName);if(existing!=null)return existing;
        return Bukkit.createWorld(new WorldCreator(worldName).environment(World.Environment.NORMAL).generator(voidGenerator()));
    }
    private void arenaCommand(Player p,String[] a){
        if(!admin(p))return;
        if(a.length==0){msg(p,"&e/eb arena create|edit|pos1|pos2|spawn|save|list");return;}
        if(a[0].equalsIgnoreCase("list")){
            for(Arena arena:arenas.values())msg(p,"&e"+arena.id+" &7world="+arena.worldName+" ready="+arena.ready()+" spawns="+arena.spawns.size());
            return;
        }
        if(a.length<2){msg(p,"&cSupply arena name.");return;}
        String id=validId(a[1]);if(id==null){msg(p,"&cInvalid arena name.");return;}
        if(a[0].equalsIgnoreCase("create")){
            if(arenas.containsKey(id)){msg(p,"&cArena already exists. Use /eb arena edit "+id);return;}
            Arena arena=new Arena(id,"eldritch_"+id);
            World world=loadWorld(arena.worldName);
            if(world==null){msg(p,"&cCould not create world.");return;}
            // Small temporary builder platform; keep it outside your arena selection.
            for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)world.getBlockAt(x,79,z).setType(Material.BEDROCK,false);
            world.setSpawnLocation(0,80,0);
            arenas.put(id,arena); saveArenas();
            p.teleport(new Location(world,0.5,80,0.5));
            msg(p,"&aVoid builder world created. Build/paste your arena, then set both corners and spawn locations.");
            return;
        }
        Arena ar=arenas.get(id);if(ar==null){msg(p,"&cUnknown arena. /eb arena list");return;}
        if(ar.busy){msg(p,"&cWait until the arena fight/reset is finished.");return;}
        switch(a[0].toLowerCase(Locale.ROOT)){
            case "edit" -> {World w=ar.world();if(w!=null)p.teleport(ar.spawns.isEmpty()?new Location(w,0.5,80,0.5):ar.spawns.get(0));}
            case "pos1" -> {if(!inArenaWorld(p,ar))return;ar.first=p.getLocation().clone();ar.baseline=null;saveArenas();msg(p,"&aCorner 1 set.");}
            case "pos2" -> {if(!inArenaWorld(p,ar))return;ar.second=p.getLocation().clone();ar.baseline=null;saveArenas();msg(p,"&aCorner 2 set.");}
            case "spawn" -> {
                if(!inArenaWorld(p,ar))return;
                if(a.length<3){msg(p,"&c/eb arena spawn "+id+" <1-16>");return;}
                int n;
                try{n=Integer.parseInt(a[2]);}catch(NumberFormatException e){msg(p,"&cSpawn must be a number.");return;}
                if(n<1||n>16){msg(p,"&cUse 1-16.");return;}
                while(ar.spawns.size()<n)ar.spawns.add(null);
                ar.spawns.set(n-1,p.getLocation().clone());saveArenas();msg(p,"&aSpawn "+n+" saved.");
            }
            case "save" -> {
                if(ar.first==null||ar.second==null||ar.spawns.size()<2||ar.spawns.contains(null)){
                    msg(p,"&cSet pos1, pos2 and sequential spawn points first.");return;}
                if(ar.volume()>getConfig().getLong("max-snapshot-blocks",250000)){
                    msg(p,"&cArena selection too large ("+ar.volume()+" blocks). Reduce it.");return;}
                if(ar.volume()<=0){msg(p,"&cInvalid selection.");return;}
                snapshot(ar);saveArenas();msg(p,"&aSaved arena template: "+ar.baseline.size()+" non-air blocks / "+ar.volume()+" total. It will reset between fights.");
            }
            default -> msg(p,"&cUnknown arena action.");
        }
    }
    private boolean inArenaWorld(Player p,Arena arena){
        if(p.getWorld().getName().equals(arena.worldName))return true;
        msg(p,"&cGo to this arena first: /eb arena edit "+arena.id);return false;
    }
    private void snapshot(Arena ar){
        List<BlockRecord> out=new ArrayList<>();World w=ar.world();
        for(int y=ar.minY();y<=ar.maxY();y++)for(int x=ar.minX();x<=ar.maxX();x++)for(int z=ar.minZ();z<=ar.maxZ();z++){
            Block b=w.getBlockAt(x,y,z);if(b.getType().isAir())continue;
            BlockState state=b.getState();ItemStack[] inv=state instanceof Container c?cloneItems(c.getInventory().getContents()):null;
            out.add(new BlockRecord(x,y,z,b.getBlockData().getAsString(),inv));
        }
        ar.baseline=out;saveSnapshot(ar);
    }
    private File snapshotFile(Arena ar){File dir=new File(getDataFolder(),"snapshots");dir.mkdirs();return new File(dir,ar.id+".bin.gz");}
    private void saveSnapshot(Arena ar){
        try(DataOutputStream o=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(snapshotFile(ar))))){
            o.writeInt(0xE1D17C);o.writeInt(ar.baseline.size());
            for(BlockRecord b:ar.baseline){
                o.writeInt(b.x);o.writeInt(b.y);o.writeInt(b.z);o.writeUTF(b.data);
                if(b.inventory==null)o.writeInt(-1);else{
                    o.writeInt(b.inventory.length);
                    for(ItemStack item:b.inventory){
                        if(item==null||item.getType().isAir())o.writeInt(-1);
                        else{byte[] bytes=item.serializeAsBytes();o.writeInt(bytes.length);o.write(bytes);}
                    }
                }
            }
        }catch(Exception e){getLogger().severe("Could not save arena snapshot "+ar.id+": "+e);}
    }
    private List<BlockRecord> loadSnapshot(Arena ar){
        File f=snapshotFile(ar);if(!f.isFile())return null;
        List<BlockRecord> blocks=new ArrayList<>();
        try(DataInputStream in=new DataInputStream(new GZIPInputStream(new FileInputStream(f)))){
            if(in.readInt()!=0xE1D17C)throw new IOException("Bad snapshot header");
            int size=in.readInt();if(size<0||size>getConfig().getLong("max-snapshot-blocks",250000))throw new IOException("Bad block count");
            for(int i=0;i<size;i++){
                int x=in.readInt(),y=in.readInt(),z=in.readInt();String data=in.readUTF();
                int len=in.readInt();if(len>54||len< -1)throw new IOException("Invalid inventory size");
                ItemStack[] items=null;
                if(len>=0){items=new ItemStack[len];for(int k=0;k<len;k++){
                    int bytes=in.readInt();if(bytes>=0){if(bytes>1000000)throw new IOException("Large item payload");
                        items[k]=ItemStack.deserializeBytes(in.readNBytes(bytes));}
                }}
                blocks.add(new BlockRecord(x,y,z,data,items));
            }
            return blocks;
        }catch(Exception e){getLogger().severe("Could not load snapshot "+ar.id+": "+e);return null;}
    }
    private void resetArena(Arena ar,Runnable complete){
        World world=ar.world();if(world==null||ar.baseline==null){getLogger().severe("Missing saved arena "+ar.id);return;}
        // Remove combat leftovers, but not players/decorative armor stands.
        for(Entity entity:world.getEntities()){
            if(entity instanceof Item || entity instanceof Projectile || entity instanceof TNTPrimed || entity instanceof EnderCrystal ||
                    entity.getType().name().contains("MINECART") && entity.getType()!=org.bukkit.entity.EntityType.MINECART){
                if(ar.inside(entity.getLocation()))entity.remove();
            }
        }
        final int minX=ar.minX(),minY=ar.minY(),minZ=ar.minZ();
        final int sx=ar.maxX()-minX+1, sz=ar.maxZ()-minZ+1;
        final long total=ar.volume();
        int rate=Math.max(100,Math.min(10000,getConfig().getInt("reset-blocks-per-tick",1200)));
        new BukkitRunnable(){
            long index=0;int put=0;int phase=0;
            @Override public void run(){
                int count=0;
                if(phase==0){
                    while(index<total && count<rate){
                        int x=minX+(int)(index%sx),z=minZ+(int)((index/sx)%sz),y=minY+(int)(index/(sx*sz));
                        world.getBlockAt(x,y,z).setType(Material.AIR,false);index++;count++;
                    }
                    if(index>=total)phase=1;
                }else{
                    while(put<ar.baseline.size() && count<rate){
                        BlockRecord r=ar.baseline.get(put++);
                        try{Block b=world.getBlockAt(r.x,r.y,r.z);b.setBlockData(Bukkit.createBlockData(r.data),false);
                            if(r.inventory!=null && b.getState() instanceof Container c){
                                c.getInventory().setContents(cloneItems(r.inventory));c.update(true,false);
                            }
                        }catch(Exception ex){getLogger().warning("Bad arena block: "+ex.getMessage());}
                        count++;
                    }
                    if(put>=ar.baseline.size()){cancel();complete.run();}
                }
            }
        }.runTaskTimer(this,1,1);
    }
    private void saveKits(){
        for(String key:new HashSet<>(kitsYaml.getKeys(false)))kitsYaml.set(key,null);
        for(Map.Entry<String,Kit> e:kits.entrySet()){
            String name=e.getKey();Kit kit=e.getValue();
            kitsYaml.set(name+".storage",Arrays.asList(kit.storage));
            kitsYaml.set(name+".armor",Arrays.asList(kit.armor));
            kitsYaml.set(name+".offhand",kit.offhand);
        }
        try{kitsYaml.save(kitsFile);}catch(IOException e){getLogger().severe("kits.yml save failed: "+e);}
    }
    private ItemStack[] readItems(List<?> list){
        ItemStack[] arr=new ItemStack[list.size()];for(int i=0;i<list.size();i++)if(list.get(i) instanceof ItemStack s)arr[i]=s;
        return arr;
    }
    private void loadKits(){
        kits.clear();for(String id:kitsYaml.getKeys(false)){
            kits.put(id,new Kit(readItems(kitsYaml.getList(id+".storage",Collections.emptyList())),
                readItems(kitsYaml.getList(id+".armor",Collections.emptyList())), kitsYaml.getItemStack(id+".offhand")));
        }
    }
    private void saveArenas(){
        for(String k:new HashSet<>(arenaYaml.getKeys(false)))arenaYaml.set(k,null);
        for(Arena a:arenas.values()){
            String path=a.id;arenaYaml.set(path+".world",a.worldName);
            arenaYaml.set(path+".pos1",a.first);arenaYaml.set(path+".pos2",a.second);
            arenaYaml.set(path+".spawns",new ArrayList<>(a.spawns));
        }
        try{arenaYaml.save(arenaFile);}catch(IOException e){getLogger().severe("arenas.yml save failed: "+e);}
    }
    private void loadArenas(){
        for(String id:arenaYaml.getKeys(false)){
            String worldName=arenaYaml.getString(id+".world","eldritch_"+id);
            World w=loadWorld(worldName);
            if(w==null)continue;
            Arena a=new Arena(id,worldName);
            a.first=arenaYaml.getLocation(id+".pos1");a.second=arenaYaml.getLocation(id+".pos2");
            for(Object obj:arenaYaml.getList(id+".spawns",Collections.emptyList()))if(obj instanceof Location loc)a.spawns.add(loc);
            if(a.first!=null&&a.second!=null)a.baseline=loadSnapshot(a);
            arenas.put(id,a);
        }
    }
    private ItemStack button(Material material,String name,String... lore){
        ItemStack stack=new ItemStack(material);ItemMeta meta=stack.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',name));
        if(lore.length>0){List<String> lines=new ArrayList<>();
            for(String l:lore)lines.add(ChatColor.translateAlternateColorCodes('&',l));meta.setLore(lines);}
        stack.setItemMeta(meta);return stack;
    }
    private void action(Menu menu,int slot,String action,ItemStack item){
        menu.inventory.setItem(slot,item);menu.actions.put(slot,action);
    }
    private void openMenu(Player player,String page){
        Party party=membership.get(player.getUniqueId());
        if(party==null){msg(player,"&cCreate a party with /eb party create first.");return;}
        if(party.match!=null){msg(player,"&cYou are currently fighting.");return;}
        Menu menu=new Menu(page,party);
        String title=switch(page){case "teams"->"ELDRITCH - Team Battle";case "kits"->"ELDRITCH - Select Kit";
            case "arenas"->"ELDRITCH - Arena";default->"ELDRITCH - Match Settings";};
        menu.inventory=Bukkit.createInventory(menu,54,title);
        for(int i=0;i<54;i++)menu.inventory.setItem(i,button(Material.GRAY_STAINED_GLASS_PANE," "));
        if(page.equals("teams")){
            for(int s=9;s<27;s++)menu.inventory.setItem(s,button(Material.RED_STAINED_GLASS_PANE,"&cTeam 1"));
            for(int s=27;s<45;s++)menu.inventory.setItem(s,button(Material.BLUE_STAINED_GLASS_PANE,"&9Team 2"));
            int red=9,blue=27,ffaSlot=9;
            for(Map.Entry<UUID,Integer> e:party.teams.entrySet()){
                if(red>=27||blue>=45||ffaSlot>=45)break;
                OfflinePlayer op=Bukkit.getOfflinePlayer(e.getKey());
                ItemStack head=new ItemStack(Material.PLAYER_HEAD);SkullMeta m=(SkullMeta)head.getItemMeta();
                m.setOwningPlayer(op);m.setDisplayName(ChatColor.YELLOW+(op.getName()==null?e.getKey().toString():op.getName()));
                m.setLore(List.of(ChatColor.GRAY+(party.ffa?"FFA — everyone for themselves":"Team "+e.getValue()),
                    ChatColor.GREEN+"Click to swap team"));head.setItemMeta(m);
                int slot=party.ffa?ffaSlot++:(e.getValue()==1?red++:blue++);
                action(menu,slot,"swap:"+e.getKey(),head);
            }
            action(menu,45,"leave",button(Material.BARRIER,"&cLeave party"));
            action(menu,49,"ffa",button(Material.NETHER_STAR,party.ffa?"&aFFA MODE ON":"&eTeam battle", "&7Click to toggle FFA/teams"));
            action(menu,53,"next",button(Material.LIME_STAINED_GLASS_PANE,"&aConfirm teams / Next"));
        }else if(page.equals("kits")){
            int i=0;
            for(String id:kits.keySet()){
                if(i>=45)break;
                Material icon= id.contains("pot")?Material.SPLASH_POTION:id.contains("mace")?Material.MACE:
                    id.contains("crystal")?Material.END_CRYSTAL:id.contains("uhc")?Material.GOLDEN_APPLE:Material.DIAMOND_SWORD;
                action(menu,i++,"kit:"+id,button(icon,"&6"+id, "&7Click to choose kit"));
            }
            action(menu,45,"back",button(Material.RED_STAINED_GLASS_PANE,"&cBack"));
            action(menu,53,"next",button(Material.LIME_STAINED_GLASS_PANE,"&aSettings", "&7Selected kit: "+(party.kit==null?"NONE":party.kit)));
        }else if(page.equals("arenas")){
            int i=0;
            for(Arena a:arenas.values()){
                if(i>=45)break;
                if(!a.ready())continue;
                action(menu,i++,"arena:"+a.id,button(Material.GRASS_BLOCK,"&e"+a.id, "&7Click to select",a.busy?"&cBusy":"&aAvailable"));
            }
            action(menu,45,"back",button(Material.RED_STAINED_GLASS_PANE,"&cBack"));
            action(menu,53,"settings",button(Material.LIME_STAINED_GLASS_PANE,"&aReturn to settings"));
        }else{
            action(menu,10,"rounds",button(Material.CLOCK,"&eBest of "+party.bestOf,"&7Click: 1 → 3 → 5 → 7"));
            action(menu,12,"arenaMenu",button(Material.GRASS_BLOCK,"&eArena: "+(party.arena==null?"None":party.arena),"&7Click to select arena"));
            action(menu,14,"friendly",button(Material.IRON_SWORD,"&eFriendly fire: "+(party.friendlyFire?"ON":"OFF")));
            action(menu,16,"drops",button(Material.HOPPER,"&eItem drops: "+(party.drops?"ON":"OFF")));
            action(menu,31,"border",button(Material.BEACON,"&e8-minute shrinking border: "+(party.border?"ON":"OFF")));
            action(menu,45,"back",button(Material.RED_STAINED_GLASS_PANE,"&cBack"));
            action(menu,53,"start",button(Material.LIME_STAINED_GLASS_PANE,"&aSTART MATCH", "&7Kit: "+party.kit,"&7Arena: "+party.arena));
        }
        player.openInventory(menu.inventory);
    }
    @EventHandler public void inventoryClick(InventoryClickEvent event){
        if(!(event.getWhoClicked() instanceof Player p))return;
        Inventory top=event.getView().getTopInventory();
        if(!(top.getHolder() instanceof Menu menu))return;
        event.setCancelled(true);
        if(event.getClickedInventory()!=top)return;
        String action=menu.actions.get(event.getRawSlot());if(action==null)return;
        Party party=membership.get(p.getUniqueId());
        if(party==null||party!=menu.party){p.closeInventory();return;}
        if(!isLeader(p,party)){msg(p,"&cOnly the party leader can configure this menu.");return;}
        String destination=menu.page;
        if(action.startsWith("swap:")){
            if(!party.ffa){UUID uuid=UUID.fromString(action.substring(5));
                party.teams.put(uuid,party.teams.getOrDefault(uuid,2)==1?2:1);}
            destination="teams";
        }else if(action.equals("ffa")){party.ffa=!party.ffa;destination="teams";}
        else if(action.startsWith("kit:")){party.kit=action.substring(4);destination="kits";}
        else if(action.startsWith("arena:")){party.arena=action.substring(6);destination="settings";}
        else switch(action){
            case "next" -> {
                if(menu.page.equals("teams"))destination="kits";
                else if(menu.page.equals("kits"))destination="settings";
            }
            case "back" -> destination=menu.page.equals("kits")?"teams":menu.page.equals("arenas")?"settings":"kits";
            case "settings" -> destination="settings";
            case "arenaMenu" -> destination="arenas";
            case "rounds" -> party.bestOf=party.bestOf==1?3:party.bestOf==3?5:party.bestOf==5?7:1;
            case "friendly" -> party.friendlyFire=!party.friendlyFire;
            case "drops" -> party.drops=!party.drops;
            case "border" -> party.border=!party.border;
            case "leave" -> {p.closeInventory();partyCommand(p,new String[]{"leave"});return;}
            case "start" -> {p.closeInventory();startMatch(p,party);return;}
            default -> {}
        }
        String next=destination;
        Bukkit.getScheduler().runTask(this,()->openMenu(p,next));
    }
    @EventHandler public void inventoryDrag(InventoryDragEvent event){
        if(event.getView().getTopInventory().getHolder() instanceof Menu)event.setCancelled(true);
    }
    private void startMatch(Player owner,Party party){
        if(!isLeader(owner,party))return;
        if(party.match!=null){msg(owner,"&cA match is already running.");return;}
        if(party.teams.size()<2){msg(owner,"&cNeed 2+ players.");return;}
        int red=0,blue=0;
        for(int t:party.teams.values())if(t==1)red++;else if(t==2)blue++;
        if(!party.ffa&&(red==0||blue==0)){msg(owner,"&cBoth teams need players. Click heads to swap teams.");return;}
        for(UUID u:party.teams.keySet()){
            Player p=Bukkit.getPlayer(u);
            if(p==null||!p.isOnline()){msg(owner,"&cAll party members must be online to start.");return;}
            if(playing.containsKey(u)){msg(owner,"&cA player is already fighting.");return;}
        }
        if(party.kit==null||!kits.containsKey(party.kit)){msg(owner,"&cSelect a saved kit first.");return;}
        Arena arena=arenas.get(party.arena);
        if(arena==null||!arena.ready()){msg(owner,"&cChoose a saved arena first.");return;}
        if(arena.busy){msg(owner,"&cArena already in use.");return;}
        if(arena.spawns.size()<party.teams.size()){msg(owner,"&cSet at least "+party.teams.size()+" spawn points for this arena.");return;}
        arena.busy=true;
        Match m=new Match(party,arena,kits.get(party.kit));party.match=m;
        for(UUID u:party.teams.keySet()){
            Player p=Bukkit.getPlayer(u);m.backups.put(u,new PlayerBackup(p));
            saveRecovery(m.backups.get(u));playing.put(u,m);
            p.closeInventory();p.setGameMode(GameMode.ADVENTURE);
            p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
        partyMsg(party,"&6Match started: "+party.kit+" | "+(party.ffa?"FFA":red+"v"+blue)+" | BO"+party.bestOf);
        beginRound(m);
    }
    private void beginRound(Match m){
        if(m.ending)return;
        m.transitioning=true;
        for(BukkitTask task:m.tasks)task.cancel();m.tasks.clear();
        m.arena.world().getWorldBorder().setSize(Math.max(100,
            Math.max(m.arena.maxX()-m.arena.minX(),m.arena.maxZ()-m.arena.minZ())+20));
        resetArena(m.arena,()->{
            if(m.ending)return;
            m.round++;m.eliminated.clear();
            List<UUID> playerOrder=new ArrayList<>(m.party.teams.keySet());
            for(int i=0;i<playerOrder.size();i++){
                Player p=Bukkit.getPlayer(playerOrder.get(i));if(p==null){m.eliminated.add(playerOrder.get(i));continue;}
                PlayerInventory inv=p.getInventory();inv.clear();
                inv.setStorageContents(cloneItems(m.kit.storage));
                inv.setArmorContents(cloneItems(m.kit.armor));
                inv.setItemInOffHand(m.kit.offhand==null?new ItemStack(Material.AIR):m.kit.offhand.clone());
                p.setGameMode(GameMode.SURVIVAL);p.setFoodLevel(20);p.setSaturation(20);
                p.setFireTicks(0);p.setFallDistance(0);
                p.setHealth(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)==null?20.0:
                    p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
                p.teleport(m.arena.spawns.get(i));
            }
            partyMsg(m.party,"&eRound "+m.round+" of BO"+m.party.bestOf+" begins in 5 seconds!");
            // Freeze by cancelling PvP damage while countdown is active.
            BukkitTask countdown=Bukkit.getScheduler().runTaskLater(this,()->{
                if(m.ending)return;
                m.transitioning=false;
                partyMsg(m.party,"&aFIGHT!");
                if(m.party.border && getConfig().getBoolean("border.enabled",true)){
                    WorldBorder b=m.arena.world().getWorldBorder();
                    b.setCenter((m.arena.minX()+m.arena.maxX())/2.0,(m.arena.minZ()+m.arena.maxZ())/2.0);
                    b.setSize(Math.max(m.arena.maxX()-m.arena.minX(),m.arena.maxZ()-m.arena.minZ())+20);
                    int delay=getConfig().getInt("border.delay-seconds",480);
                    BukkitTask borderTask=Bukkit.getScheduler().runTaskLater(this,()->{
                        if(!m.ending&&!m.transitioning){
                            b.setSize(getConfig().getDouble("border.final-size",10),
                                getConfig().getLong("border.shrink-seconds",180));
                            partyMsg(m.party,"&cWorld border is shrinking!");
                        }
                    },20L*Math.max(1,delay));m.tasks.add(borderTask);
                }
            },100L);m.tasks.add(countdown);
            // Mark a quit during preparation as a forfeit.
            Bukkit.getScheduler().runTaskLater(this,()->checkRound(m),101L);
        });
    }
    @EventHandler public void damage(EntityDamageByEntityEvent event){
        if(!(event.getEntity() instanceof Player hurt))return;
        Match m=playing.get(hurt.getUniqueId());if(m==null)return;
        if(m.ending||m.transitioning||m.eliminated.contains(hurt.getUniqueId())){event.setCancelled(true);return;}
        Entity source=event.getDamager();
        Player attacker=source instanceof Player p?p:source instanceof Projectile projectile&&projectile.getShooter() instanceof Player p?p:null;
        if(attacker!=null&&m.party.teams.containsKey(attacker.getUniqueId())&& !m.party.ffa
            && m.side(attacker.getUniqueId()).equals(m.side(hurt.getUniqueId()))&&!m.party.friendlyFire){
            event.setCancelled(true);
        }
    }
    @EventHandler public void onDeath(PlayerDeathEvent event){
        Player p=event.getEntity();Match m=playing.get(p.getUniqueId());if(m==null)return;
        if(!m.party.drops){event.setKeepInventory(true);event.getDrops().clear();}
        event.setKeepLevel(true);event.setDroppedExp(0);
        m.eliminated.add(p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this,()->checkRound(m),2L);
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent event){
        Player player=event.getPlayer();
        Match m=playing.get(player.getUniqueId());
        if(m==null){
            if(recoveryYaml.contains(player.getUniqueId().toString())){
                Location saved=recoveryYaml.getLocation(player.getUniqueId()+".location");
                if(saved!=null && saved.getWorld()!=null)event.setRespawnLocation(saved);
                Bukkit.getScheduler().runTaskLater(this,()->restoreRecovery(player),2L);
            }
            return;
        }
        event.setRespawnLocation(Bukkit.getWorlds().get(0).getSpawnLocation());
        Bukkit.getScheduler().runTaskLater(this,()->{
            if(playing.get(player.getUniqueId())==m && m.eliminated.contains(player.getUniqueId()))
                player.setGameMode(GameMode.SPECTATOR);
        },1L);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event){
        Player p=event.getPlayer();invitations.remove(p.getUniqueId());
        Match m=playing.get(p.getUniqueId());
        if(m!=null){m.eliminated.add(p.getUniqueId());
            Bukkit.getScheduler().runTaskLater(this,()->checkRound(m),2L);}
    }
    @EventHandler public void onJoin(PlayerJoinEvent event){
        Player p=event.getPlayer();
        if(recoveryYaml.contains(p.getUniqueId().toString()) && !playing.containsKey(p.getUniqueId())){
            Bukkit.getScheduler().runTaskLater(this,()->restoreRecovery(p),2L);
        }
    }
    private void checkRound(Match m){
        if(m.ending||m.transitioning)return;
        Set<String> alive=new HashSet<>();
        for(UUID u:m.party.teams.keySet()){
            if(!m.eliminated.contains(u) && Bukkit.getPlayer(u)!=null)alive.add(m.side(u));
        }
        if(alive.size()>1)return;
        m.transitioning=true;
        for(BukkitTask task:m.tasks)task.cancel();m.tasks.clear();
        if(alive.isEmpty()){partyMsg(m.party,"&eRound drawn; replaying.");m.round--; waitAndNext(m);return;}
        String winner=alive.iterator().next();int score=m.wins.merge(winner,1,Integer::sum);
        String name=m.party.ffa ? Bukkit.getOfflinePlayer(UUID.fromString(winner)).getName() :
            "Team "+winner.substring(4);
        partyMsg(m.party,"&6"+name+" &awon round "+m.round+"! ("+score+" win(s))");
        if(score>=m.party.bestOf/2+1){finishMatch(m,winner);return;}
        waitAndNext(m);
    }
    private void waitAndNext(Match m){
        for(UUID u:m.party.teams.keySet()){
            Player p=Bukkit.getPlayer(u);if(p!=null){p.setGameMode(GameMode.ADVENTURE);p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());}
        }
        Bukkit.getScheduler().runTaskLater(this,()->{if(!m.ending)beginRound(m);},60L);
    }
    private void finishMatch(Match m,String winner){
        if(m.ending)return;m.ending=true;
        for(BukkitTask task:m.tasks)task.cancel();m.tasks.clear();
        String winnerName=m.party.ffa?Bukkit.getOfflinePlayer(UUID.fromString(winner)).getName():"Team "+winner.substring(4);
        Bukkit.broadcastMessage(prefix+ChatColor.GOLD+"MATCH WINNER: "+ChatColor.GREEN+winnerName+ChatColor.GOLD+" — "+m.party.kit);
        for(UUID u:m.party.teams.keySet()){
            playing.remove(u);Player p=Bukkit.getPlayer(u);PlayerBackup backup=m.backups.get(u);
            // A dead player must respawn before their kit inventory can be safely replaced.
            // recover-inventory.yml remains available until that respawn (or reconnect).
            if(p!=null&&backup!=null && !p.isDead())restore(p,backup);
        }
        m.party.match=null;
        m.arena.world().getWorldBorder().reset();
        resetArena(m.arena,()->{m.arena.busy=false;getLogger().info("Arena "+m.arena.id+" reset after match.");});
    }
    private void restore(Player p,PlayerBackup b){
        p.getInventory().clear();p.getInventory().setStorageContents(cloneItems(b.inventory.storage));
        p.getInventory().setArmorContents(cloneItems(b.inventory.armor));
        p.getInventory().setItemInOffHand(b.inventory.offhand==null?new ItemStack(Material.AIR):b.inventory.offhand.clone());
        p.setGameMode(b.gamemode);p.setFoodLevel(b.food);p.setFireTicks(0);
        if(b.location.getWorld()!=null)p.teleport(b.location);
        recoveryYaml.set(p.getUniqueId().toString(),null);flushRecovery();
    }
    private void saveRecovery(PlayerBackup b){
        String key=b.uuid.toString();recoveryYaml.set(key+".location",b.location);
        recoveryYaml.set(key+".gamemode",b.gamemode.name());
        recoveryYaml.set(key+".storage",Arrays.asList(b.inventory.storage));
        recoveryYaml.set(key+".armor",Arrays.asList(b.inventory.armor));
        recoveryYaml.set(key+".offhand",b.inventory.offhand);
        recoveryYaml.set(key+".food",b.food);flushRecovery();
    }
    private void flushRecovery(){
        try{recoveryYaml.save(recoveryFile);}catch(IOException e){getLogger().severe("Recovery save failed: "+e);}
    }
    private void restoreRecovery(Player p){
        String key=p.getUniqueId().toString();if(!recoveryYaml.contains(key))return;
        Location loc=recoveryYaml.getLocation(key+".location");
        GameMode mode=GameMode.valueOf(recoveryYaml.getString(key+".gamemode","SURVIVAL"));
        Kit inv=new Kit(readItems(recoveryYaml.getList(key+".storage",Collections.emptyList())),
            readItems(recoveryYaml.getList(key+".armor",Collections.emptyList())), recoveryYaml.getItemStack(key+".offhand"));
        p.getInventory().clear();p.getInventory().setStorageContents(inv.storage);
        p.getInventory().setArmorContents(inv.armor);p.getInventory().setItemInOffHand(inv.offhand==null?new ItemStack(Material.AIR):inv.offhand);
        p.setGameMode(mode);p.setFoodLevel(recoveryYaml.getInt(key+".food",20));
        if(loc!=null&&loc.getWorld()!=null)p.teleport(loc);
        recoveryYaml.set(key,null);flushRecovery();msg(p,"&aYour inventory was restored after the previous battle.");
    }
}
