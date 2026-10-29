package dev.eldritch.battles;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.attribute.Attribute;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.EventPriority;
import io.papermc.paper.event.player.PlayerShieldDisableEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.scoreboard.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** First public test build. No dependence on EpicDuels or Custom Teams. */
@SuppressWarnings("deprecation")
public final class EldritchBattles extends JavaPlugin implements Listener, TabCompleter {
    private final Map<UUID, Party> membership = new HashMap<>();
    private final Map<UUID, Match> playing = new HashMap<>();
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<String, Kit> kits = new LinkedHashMap<>();
    private final Map<String, Material> kitIcons = new HashMap<>();
    private final Map<UUID, UUID> invitations = new HashMap<>();
    private final Map<UUID, Long> lastBroadcast = new HashMap<>();
    private Arena worldArena;
    private final Map<UUID, Party> duelRequests = new HashMap<>();
    private final Map<UUID, UUID> duelTargets = new HashMap<>();
    private final LinkedHashMap<String, MatchReport> reports = new LinkedHashMap<>();
    private File arenaFile, kitsFile, recoveryFile;
    private YamlConfiguration arenaYaml, kitsYaml, recoveryYaml;
    private final String prefix = ChatColor.GOLD + "[Eldritch] " + ChatColor.RESET;

    static class Party {
        UUID owner;
        final LinkedHashMap<UUID, Integer> teams = new LinkedHashMap<>();
        boolean ffa = false, friendlyFire = false, drops = false, border = true;
        boolean healthIndicator = true, shieldStuns = true, infiniteRounds = false;
        boolean directDuel = false, open = false;
        int bestOf = 3; // Count of rounds, NOT best-of series. Saved field name retained for compatibility.
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
        boolean worldMode() { return id.equals("world"); }
        boolean ready() { if(worldMode())return world()!=null; return first != null && second != null && spawns.size() >= 2
            && spawns.get(0) != null && spawns.get(1) != null && baseline != null && world() != null; }
        int minX(){return Math.min(first.getBlockX(), second.getBlockX());}
        int maxX(){return Math.max(first.getBlockX(), second.getBlockX());}
        int minY(){return Math.min(first.getBlockY(), second.getBlockY());}
        int maxY(){return Math.max(first.getBlockY(), second.getBlockY());}
        int minZ(){return Math.min(first.getBlockZ(), second.getBlockZ());}
        int maxZ(){return Math.max(first.getBlockZ(), second.getBlockZ());}
        long volume(){return (long)(maxX()-minX()+1)*(maxY()-minY()+1)*(maxZ()-minZ()+1);}
        boolean inside(Location l) {if(worldMode())return l!=null && l.getWorld()==world();return l!=null && l.getWorld()!=null && l.getWorld().getName().equals(worldName)
            && l.getBlockX()>=minX() && l.getBlockX()<=maxX() && l.getBlockY()>=minY() && l.getBlockY()<=maxY()
            && l.getBlockZ()>=minZ() && l.getBlockZ()<=maxZ();}
    }
    /** World is implicit: each arena has its own dedicated builder/duel world. */
    record BlockKey(int x, int y, int z) {
        static BlockKey of(Block block){return new BlockKey(block.getX(),block.getY(),block.getZ());}
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
        final Scoreboard scoreboard;
        PlayerBackup(Player p){
            uuid=p.getUniqueId(); location=p.getLocation().clone(); gamemode=p.getGameMode();
            inventory=new Kit(p.getInventory().getStorageContents(),p.getInventory().getArmorContents(),p.getInventory().getItemInOffHand());
            food=p.getFoodLevel(); scoreboard=p.getScoreboard();
        }
    }
    static class Match {
        final Party party; final Arena arena; final Kit kit;
        final Set<UUID> eliminated = new HashSet<>();
        final Map<String,Integer> wins=new HashMap<>();
        final Map<UUID,PlayerBackup> backups=new HashMap<>();
        final Map<UUID,Kit> lastInventories=new HashMap<>();
        final Map<UUID,Location> worldSpawns=new LinkedHashMap<>();
        final Map<UUID,Integer> deaths=new HashMap<>(), kills=new HashMap<>();
        final List<BukkitTask> tasks = new ArrayList<>();
        // Only blocks placed while this match is active may be mined/exploded in this arena world.
        final Set<BlockKey> placedBlocks = new HashSet<>();
        Scoreboard healthBoard;
        int round=0; boolean ending=false, transitioning=true;
        Match(Party p,Arena a,Kit k){party=p;arena=a;kit=k;}
        String side(UUID u) {return party.ffa ? u.toString() : "team" + party.teams.getOrDefault(u,0);}
    }
    static class MatchReport {
        final String id = UUID.randomUUID().toString().substring(0,8);
        final String kitName, arenaName, winner, score;
        final Map<UUID, Kit> inventories = new LinkedHashMap<>();
        final Map<UUID, Integer> kills = new LinkedHashMap<>(), deaths = new LinkedHashMap<>();
        MatchReport(Match m,String winnerName){
            this.kitName=m.party.kit; this.arenaName=m.arena.id; this.winner=winnerName;
            this.score=m.wins.toString();
            for(UUID u:m.party.teams.keySet()){
                Kit snapshot=m.lastInventories.get(u);
                if(snapshot!=null)inventories.put(u,snapshot);
                kills.put(u,m.kills.getOrDefault(u,0)); deaths.put(u,m.deaths.getOrDefault(u,0));
            }
        }
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
        worldArena=new Arena("world",getConfig().getString("world-duels.world","world"));
        // Restore a sane border after an interrupted match/restart in a plugin-owned arena world.
        for(Arena arena:arenas.values())if(!arena.worldMode() && arena.world()!=null)arena.world().getWorldBorder().reset();
        getServer().getPluginManager().registerEvents(this,this);
        getLogger().info("Eldritch Battles v0.2.5 enabled. Use /eb help");
    }
    @Override public void onDisable(){
        for(Match match: new HashSet<>(playing.values())){
            for(PlayerBackup b: match.backups.values()){
                Player p=Bukkit.getPlayer(b.uuid);
                if(p!=null && !p.isDead()) restore(p,b);
            }
            for(BukkitTask t:match.tasks)t.cancel();
            match.placedBlocks.clear();
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
        msg(p,"&6/duel <player> &7(1v1 challenge; /duel accept <player>)");
        msg(p,"&6/party create | invite <player> | join <owner> | leave | list");
        msg(p,"&6/party open | close | broadcast | kick <player> | promote <player>");
        msg(p,"&e/eb menu &7(party home / team battles)");
        msg(p,"&e/eb stop &7(end your infinite-round match)");
        msg(p,"&e/eb kit save|icon|list|delete <name> &7(admin)");
        msg(p,"&e/eb arena create|edit|pos1|pos2|spawn|save|list|inspect|recover &7(admin)");
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof Player p)){msg(sender,"Players must use this command in game.");return true;}
        try {
            if(command.getName().equalsIgnoreCase("duel")){duelCommand(p,args);return true;}
            if(command.getName().equalsIgnoreCase("party")){partyCommand(p,args);return true;}
            if(args.length==0 || args[0].equalsIgnoreCase("help")){help(p);return true;}
            switch(args[0].toLowerCase(Locale.ROOT)) {
                case "menu" -> openMenu(p,"home");
                case "duel" -> duelCommand(p,Arrays.copyOfRange(args,1,args.length));
                case "party" -> partyCommand(p,Arrays.copyOfRange(args,1,args.length));
                case "kit" -> kitCommand(p,Arrays.copyOfRange(args,1,args.length));
                case "arena" -> arenaCommand(p,Arrays.copyOfRange(args,1,args.length));
                case "stop" -> stopMatch(p);
                case "report" -> reportCommand(p,Arrays.copyOfRange(args,1,args.length));
                default -> help(p);
            }
        } catch(Exception ex){
            msg(p,"&cCommand failed: "+ex.getMessage());
            getLogger().warning("Command error for "+p.getName()+": "+ex);
            ex.printStackTrace();
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command cmd,String alias,String[] a){
        List<String> values=new ArrayList<>();
        String root=cmd.getName().toLowerCase(Locale.ROOT);
        if(root.equals("duel")){
            if(a.length==1){values.addAll(List.of("accept","deny","cancel"));for(Player p:Bukkit.getOnlinePlayers())values.add(p.getName());}
            else if(a.length==2 && (a[0].equalsIgnoreCase("accept")||a[0].equalsIgnoreCase("deny")))
                for(Player p:Bukkit.getOnlinePlayers())values.add(p.getName());
        }else if(root.equals("party")){
            if(a.length==1)values.addAll(List.of("create","invite","join","leave","disband","list","menu","open","close","broadcast","kick","promote"));
            if(a.length==2&&(a[0].equalsIgnoreCase("invite")||a[0].equalsIgnoreCase("join")||a[0].equalsIgnoreCase("kick")||a[0].equalsIgnoreCase("promote")))
                for(Player p:Bukkit.getOnlinePlayers())values.add(p.getName());
        }else if(a.length==1)values.addAll(List.of("menu","duel","party","kit","arena","stop","report","help"));
        else if(a.length==2){
            switch(a[0].toLowerCase(Locale.ROOT)){
                case "duel" -> {values.addAll(List.of("accept","deny","cancel"));for(Player p:Bukkit.getOnlinePlayers())values.add(p.getName());}
                case "party" -> values.addAll(List.of("create","invite","join","leave","disband","list","menu","open","close","broadcast","kick","promote"));
                case "kit" -> values.addAll(List.of("save","list","delete","icon"));
                case "arena" -> values.addAll(List.of("create","edit","pos1","pos2","spawn","save","list","inspect","recover"));
            }
        }else if(a.length==3){
            if(a[0].equalsIgnoreCase("kit"))values.addAll(kits.keySet());
            if(a[0].equalsIgnoreCase("arena"))values.addAll(arenas.keySet());
            if(a[0].equalsIgnoreCase("party")&&(a[1].equalsIgnoreCase("invite")||a[1].equalsIgnoreCase("join")||a[1].equalsIgnoreCase("kick")||a[1].equalsIgnoreCase("promote")))
                for(Player p:Bukkit.getOnlinePlayers())values.add(p.getName());
        }else if(a.length==4 && a[0].equalsIgnoreCase("arena") && a[1].equalsIgnoreCase("spawn"))
            values.addAll(List.of("1","2"));
        String prefix=a[a.length-1].toLowerCase(Locale.ROOT);
        return values.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().sorted().toList();
    }
    private void duelCommand(Player p,String[] args){
        if(args.length==0){msg(p,"&e/duel <player> | accept <player> | deny <player> | cancel");return;}
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("accept")||sub.equals("deny")){
            Party challenge=duelRequests.get(p.getUniqueId());
            if(challenge==null){msg(p,"&cNo pending duel challenge.");return;}
            Player host=Bukkit.getPlayer(challenge.owner);
            if(args.length>1 && (host==null||!host.getName().equalsIgnoreCase(args[1]))){msg(p,"&cNo duel challenge from that player.");return;}
            if(sub.equals("deny")){cancelDuel(challenge,"&cDuel request declined.");return;}
            if(membership.containsKey(p.getUniqueId()) || playing.containsKey(p.getUniqueId())){
                msg(p,"&cLeave your party/finish your current match before accepting.");return;
            }
            duelRequests.remove(p.getUniqueId());duelTargets.remove(challenge.owner);
            challenge.teams.put(p.getUniqueId(),2);membership.put(p.getUniqueId(),challenge);
            msg(p,"&aChallenge accepted!");
            if(host==null){cancelDuel(challenge,"&cChallenger left the server.");return;}
            startMatch(host,challenge);return;
        }
        if(sub.equals("cancel")){
            Party party=membership.get(p.getUniqueId());
            if(party!=null && party.directDuel && party.match==null && party.owner.equals(p.getUniqueId()))
                cancelDuel(party,"&eDuel cancelled.");
            else msg(p,"&cNo pending duel to cancel.");
            return;
        }
        Player target=Bukkit.getPlayerExact(args[0]);
        if(target==null || target.equals(p)){msg(p,"&cChoose another online player.");return;}
        if(membership.containsKey(p.getUniqueId()) || playing.containsKey(p.getUniqueId())){
            msg(p,"&cLeave your current party or finish your match before a direct duel.");return;
        }
        if(membership.containsKey(target.getUniqueId()) || playing.containsKey(target.getUniqueId())){
            msg(p,"&cThat player is already in a party or match.");return;
        }
        Party challenge=new Party(p.getUniqueId());challenge.directDuel=true;
        challenge.arena=null; // random available arena unless explicitly selected
        membership.put(p.getUniqueId(),challenge);
        duelTargets.put(p.getUniqueId(),target.getUniqueId());
        msg(p,"&aSelect a kit, choose random/specific arena, then START MATCH to send the challenge.");
        openMenu(p,"kits");
    }
    private void sendDuelChallenge(Player host,Party party){
        UUID targetUuid=duelTargets.get(host.getUniqueId());
        Player target=targetUuid==null?null:Bukkit.getPlayer(targetUuid);
        if(target==null || membership.containsKey(targetUuid) || playing.containsKey(targetUuid)){
            msg(host,"&cTarget is unavailable. /duel cancel to exit.");return;
        }
        if(party.kit==null || !kits.containsKey(party.kit)){msg(host,"&cChoose a kit first.");return;}
        Arena arena=resolveArena(party);
        if(arena==null){msg(host,"&cNo ready, free arena. Check /eb arena inspect test.");return;}
        if(duelRequests.containsKey(targetUuid)){msg(host,"&cThat player already has a pending challenge.");return;}
        duelRequests.put(targetUuid,party);
        msg(host,"&eChallenge sent to &f"+target.getName()+"&e. Awaiting acceptance.");
        // Lock the arena actually offered, so the accepted challenge cannot silently switch maps.
        party.arena=arena.id;
        String mode=party.ffa?"FFA":"1v1";
        String rounds=party.infiniteRounds?"Infinite":Integer.toString(party.bestOf);
        target.sendMessage(Component.text("✧ DUEL REQUEST ✧",NamedTextColor.GOLD));
        target.sendMessage(Component.text(host.getName()+" wants to duel you.",NamedTextColor.YELLOW));
        target.sendMessage(Component.text("Gamemode: "+party.kit+" ("+mode+")",NamedTextColor.GRAY));
        target.sendMessage(Component.text("Arena: "+arena.id+"   Rounds: "+rounds,NamedTextColor.GRAY));
        target.sendMessage(Component.text("[CLICK TO ACCEPT]",NamedTextColor.GREEN)
            .clickEvent(ClickEvent.runCommand("/eb duel accept "+host.getName()))
            .hoverEvent(HoverEvent.showText(Component.text("Accept duel from "+host.getName())))
            .append(Component.text("    "))
            .append(Component.text("[DECLINE]",NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/eb duel deny "+host.getName()))));
        Bukkit.getScheduler().runTaskLater(this,()->{
            if(duelRequests.get(targetUuid)==party)cancelDuel(party,"&cDuel request expired after 60 seconds.");
        },1200L);
    }
    private void cancelDuel(Party party,String reason){
        UUID target=duelTargets.remove(party.owner);
        if(target!=null)duelRequests.remove(target,party);
        if(party.match!=null)return;
        for(UUID member:new ArrayList<>(party.teams.keySet()))membership.remove(member,party);
        partyMsg(party,reason);
        Player p=target==null?null:Bukkit.getPlayer(target);
        if(p!=null)msg(p,reason);
    }
    private void openDuelRequest(Player target,Party party){
        Menu m=new Menu("duelRequest",party);
        m.inventory=Bukkit.createInventory(m,27,"Eldritch - Duel Challenge");
        m.inventory.setItem(13,button(Material.NETHERITE_SWORD,"&6Duel vs "+Bukkit.getOfflinePlayer(party.owner).getName(),
            "&eKit: "+party.kit,"&eArena: "+(party.arena==null?"Random":party.arena)));
        action(m,11,"acceptDuel",button(Material.LIME_CONCRETE,"&aACCEPT"));
        action(m,15,"denyDuel",button(Material.BARRIER,"&cDECLINE"));
        target.openInventory(m.inventory);
    }
    private void stopMatch(Player player){
        Match m=playing.get(player.getUniqueId());
        if(m==null || !m.party.owner.equals(player.getUniqueId())){msg(player,"&cOnly the host can stop an ongoing match.");return;}
        finishMatch(m,winningSide(m));
    }
    private void partyCommand(Player p,String[] args){
        if(args.length==0){openMenu(p,"home");return;}
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
                target.sendMessage(Component.text(p.getName()+" invited you to their party! ",NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text("[CLICK TO JOIN]",NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/eb party join "+p.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text("Join "+p.getName()+"'s party")))));
                msg(p,"&aInvite sent to "+target.getName());
            }
            case "join" -> {
                if(party!=null||args.length<2){msg(p,"&cUsage: /eb party join <host>; leave current party first.");return;}
                Player host=Bukkit.getPlayerExact(args[1]);
                if(host==null){msg(p,"&cHost is offline.");return;}
                Party joined=membership.get(host.getUniqueId());
                if(joined==null || !joined.owner.equals(host.getUniqueId()) || joined.match!=null){msg(p,"&cParty unavailable.");return;}
                if(!joined.open && !host.getUniqueId().equals(invitations.get(p.getUniqueId()))){
                    msg(p,"&cThat party is private. Ask the owner to invite you.");return;
                }
                if(joined.teams.size()>=16){msg(p,"&cParty is full.");return;}
                joined.teams.put(p.getUniqueId(),2);membership.put(p.getUniqueId(),joined);
                invitations.remove(p.getUniqueId());partyMsg(joined,"&a"+p.getName()+" joined the party!");
            }
            case "leave", "disband" -> {
                if(party==null){msg(p,"&cNot in a party.");return;}
                if(party.match!=null){msg(p,"&cCan't leave during an active match.");return;}
                if(party.owner.equals(p.getUniqueId())){
                    for(UUID u:party.teams.keySet())membership.remove(u);
                    partyMsg(party,"&cParty disbanded.");
                } else {party.teams.remove(p.getUniqueId());membership.remove(p.getUniqueId());partyMsg(party,"&e"+p.getName()+" left.");}
            }
            case "open", "close" -> {
                if(!isLeader(p,party)){msg(p,"&cOnly the leader can change party privacy.");return;}
                party.open=args[0].equalsIgnoreCase("open");
                partyMsg(party,party.open?"&aParty is PUBLIC. Anyone can /party join "+p.getName():
                    "&eParty is PRIVATE. Players need an invitation.");
            }
            case "broadcast" -> {
                if(!isLeader(p,party) || party.match!=null){msg(p,"&cOnly a free party leader can broadcast.");return;}
                if(!party.open){msg(p,"&cOpen the party before broadcasting: /party open");return;}
                long now=System.currentTimeMillis();
                if(now-lastBroadcast.getOrDefault(party.owner,0L)<30000){msg(p,"&cWait 30 seconds between broadcasts.");return;}
                lastBroadcast.put(party.owner,now);
                Component announcement=Component.text("✧ "+p.getName()+"'s party is open to the public! ",NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text("[Click to join]",NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/eb party join "+p.getName())));
                for(Player online:Bukkit.getOnlinePlayers())online.sendMessage(announcement);
            }
            case "kick", "promote" -> {
                if(!isLeader(p,party) || party.match!=null){msg(p,"&cOnly a leader outside a match can manage members.");return;}
                if(args.length<2){msg(p,"&cUsage: /party "+args[0]+" <member>");return;}
                Player target=Bukkit.getPlayerExact(args[1]);
                if(target==null || !party.teams.containsKey(target.getUniqueId()) || target.getUniqueId().equals(party.owner)){
                    msg(p,"&cChoose another online party member.");return;
                }
                if(args[0].equalsIgnoreCase("kick")){
                    party.teams.remove(target.getUniqueId());membership.remove(target.getUniqueId(),party);
                    msg(target,"&cYou were kicked from the party.");partyMsg(party,"&e"+target.getName()+" was kicked.");
                }else{
                    party.owner=target.getUniqueId();
                    // Invites tied to the old leader no longer apply to this party.
                    invitations.entrySet().removeIf(e->e.getValue().equals(p.getUniqueId()));
                    partyMsg(party,"&a"+target.getName()+" is now the party leader!");
                }
            }
            case "list" -> {
                if(party==null){msg(p,"&cNot in a party.");return;}
                msg(p,"&6Party members: "+party.teams.size());
                for(Map.Entry<UUID,Integer> e:party.teams.entrySet()){
                    OfflinePlayer op=Bukkit.getOfflinePlayer(e.getKey());
                    msg(p,"&7- "+op.getName()+" : "+(party.ffa?"FFA": "Team "+e.getValue()));
                }
            }
            case "menu" -> openMenu(p,"home");
            default -> msg(p,"&c/eb party create|invite|join|leave|list|menu");
        }
    }
    private boolean isLeader(Player p,Party party){return party!=null&&party.owner.equals(p.getUniqueId());}
    private void kitCommand(Player p,String[] a){
        if(a.length==0){msg(p,"&e/eb kit save <name> | list | delete <name> | icon <name> [item]");return;}
        if(a[0].equalsIgnoreCase("list")){msg(p,"&eSaved kits: "+String.join(", ",kits.keySet()));return;}
        if(!admin(p))return;
        if(a.length<2){msg(p,"&cSpecify kit name, e.g. /eb kit save sword");return;}
        String id=validId(a[1]);if(id==null){msg(p,"&cUse letters, numbers, underscore or dash.");return;}
        if(a[0].equalsIgnoreCase("save")){
            PlayerInventory inv=p.getInventory();
            kits.put(id,new Kit(inv.getStorageContents(),inv.getArmorContents(),inv.getItemInOffHand()));
            saveKits();msg(p,"&aSaved kit '&f"+id+"&a' including empty slots, armour and offhand.");
        }else if(a[0].equalsIgnoreCase("icon")){
            if(!kits.containsKey(id)){msg(p,"&cSave that kit first using /eb kit save " + id);return;}
            Material material;
            if(a.length>=3){
                material=Material.matchMaterial(a[2]);
                if(material==null || !material.isItem() || material.isAir()){
                    msg(p,"&cUnknown icon item. Example: /eb kit icon " + id + " mace");return;
                }
            }else{
                ItemStack held=p.getInventory().getItemInMainHand();
                if(held.getType().isAir()){
                    msg(p,"&cHold the item you want as the kit icon first.");return;
                }
                material=held.getType();
            }
            kitIcons.put(id,material);
            saveKits();
            msg(p,"&aSet the '&f"+id+"&a' menu icon to &f"+material.name()+"&a. Reopen /eb menu to see it.");
        }else if(a[0].equalsIgnoreCase("delete")){
            kits.remove(id);kitIcons.remove(id);saveKits();msg(p,"&eKit deleted.");
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
            if(worldArena!=null)msg(p,"&eWorld &7normal world="+worldArena.worldName+" available="+worldArena.ready()+" (no snapshot required)");
            return;
        }
        if(a.length<2){msg(p,"&cSupply arena name.");return;}
        String id=validId(a[1]);if(id==null){msg(p,"&cInvalid arena name.");return;}
        if(a[0].equalsIgnoreCase("create")){
            if(id.equals("world")){msg(p,"&cWorld is a special no-reset duel option; it cannot be created as a builder arena.");return;}
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
            case "inspect" -> {
                msg(p,"&6Arena "+ar.id+": world="+ar.worldName+", loaded="+(ar.world()!=null));
                msg(p,"&7pos1="+(ar.first==null?"MISSING":ar.first.toVector())+" pos2="+(ar.second==null?"MISSING":ar.second.toVector()));
                msg(p,"&7spawns="+ar.spawns.size()+" snapshot="+(ar.baseline==null?"MISSING":"YES, "+ar.baseline.size()+" blocks"));
                msg(p,"&eIf snapshot exists but corners are missing, try /eb arena recover "+ar.id);
            }
            case "recover" -> {
                if(ar.ready()){msg(p,"&aArena already ready; no recovery needed.");return;}
                List<BlockRecord> records=loadSnapshot(ar);
                if(records==null||records.isEmpty()){msg(p,"&cNo snapshot found. Set corners and /eb arena save "+id);return;}
                int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
                for(BlockRecord b:records){minX=Math.min(minX,b.x);maxX=Math.max(maxX,b.x);
                    minY=Math.min(minY,b.y);maxY=Math.max(maxY,b.y);minZ=Math.min(minZ,b.z);maxZ=Math.max(maxZ,b.z);}
                Location first=new Location(ar.world(),minX,minY,minZ),second=new Location(ar.world(),maxX,maxY,maxZ);
                long volume=(long)(maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1);
                if(volume>getConfig().getLong("max-snapshot-blocks",250000)){
                    msg(p,"&cSaved block bounds too large to infer safely. Set corners manually; DO NOT overwrite a backup.");return;
                }
                ar.first=first;ar.second=second;ar.baseline=records;saveArenas();
                msg(p,"&aRecovered arena corners from snapshot, volume="+volume+". No blocks were changed.");
            }
            case "edit" -> {World w=ar.world();if(w!=null)p.teleport(ar.spawns.isEmpty() || ar.spawns.get(0)==null?
                new Location(w,0.5,80,0.5):ar.spawns.get(0));}
            case "pos1" -> {if(!inArenaWorld(p,ar))return;ar.first=p.getLocation().clone();ar.baseline=null;saveArenas();msg(p,"&aCorner 1 set.");}
            case "pos2" -> {if(!inArenaWorld(p,ar))return;ar.second=p.getLocation().clone();ar.baseline=null;saveArenas();msg(p,"&aCorner 2 set.");}
            case "spawn" -> {
                if(!inArenaWorld(p,ar))return;
                if(a.length<3){msg(p,"&c/eb arena spawn "+id+" <1-16>");return;}
                int n;
                try{n=Integer.parseInt(a[2]);}catch(NumberFormatException e){msg(p,"&cSpawn must be a number.");return;}
                if(n<1||n>16){msg(p,"&cUse 1-16.");return;}
                while(ar.spawns.size()<n)ar.spawns.add(null);
                ar.spawns.set(n-1,p.getLocation().clone());saveArenas();msg(p,"&aSpawn "+n+" saved"+(n<=2?" for Team "+n:" (legacy extra spawn)")+".");
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
    /** Restore only changed baseline blocks and player-placed blocks, instead of clearing the entire cuboid.
     * Batch the scan to keep the main server thread responsive. */
    private void resetArena(Arena ar,Set<BlockKey> placed,Runnable complete){
        if(ar.worldMode()){complete.run();return;}
        World world=ar.world();
        if(world==null || ar.baseline==null){getLogger().severe("Missing saved arena "+ar.id);complete.run();return;}
        for(Entity entity:world.getEntities()){
            if((entity instanceof Item || entity instanceof Projectile || entity instanceof TNTPrimed ||
                entity instanceof EnderCrystal || entity.getType().name().contains("MINECART"))
                && ar.inside(entity.getLocation()))entity.remove();
        }
        final Map<BlockKey,BlockRecord> originals=new HashMap<>();
        for(BlockRecord record:ar.baseline)originals.put(new BlockKey(record.x,record.y,record.z),record);
        final List<BlockKey> additions=new ArrayList<>();
        for(BlockKey key:placed)if(!originals.containsKey(key))additions.add(key);
        final int rate=Math.max(200,Math.min(20000,getConfig().getInt("reset-blocks-per-tick",5000)));
        new BukkitRunnable(){
            int clear=0,scan=0;
            @Override public void run(){
                int count=0;
                while(clear<additions.size() && count<rate){
                    BlockKey key=additions.get(clear++);
                    world.getBlockAt(key.x(),key.y(),key.z()).setType(Material.AIR,false);
                    count++;
                }
                while(scan<ar.baseline.size() && count<rate){
                    BlockRecord original=ar.baseline.get(scan++);
                    Block block=world.getBlockAt(original.x,original.y,original.z);
                    try{
                        if(!block.getBlockData().getAsString().equals(original.data))
                            block.setBlockData(Bukkit.createBlockData(original.data),false);
                        if(original.inventory!=null && block.getState() instanceof Container c){
                            if(!Arrays.equals(c.getInventory().getContents(),original.inventory)){
                                c.getInventory().setContents(cloneItems(original.inventory));c.update(true,false);
                            }
                        }
                    }catch(Exception ex){getLogger().warning("Could not restore arena block: "+ex.getMessage());}
                    count++;
                }
                if(clear>=additions.size() && scan>=ar.baseline.size()){cancel();complete.run();}
            }
        }.runTaskTimer(this,1L,1L);
    }
    private void saveKits(){
        for(String key:new HashSet<>(kitsYaml.getKeys(false)))kitsYaml.set(key,null);
        for(Map.Entry<String,Kit> e:kits.entrySet()){
            String name=e.getKey();Kit kit=e.getValue();
            kitsYaml.set(name+".storage",Arrays.asList(kit.storage));
            kitsYaml.set(name+".armor",Arrays.asList(kit.armor));
            kitsYaml.set(name+".offhand",kit.offhand);
            if(kitIcons.containsKey(name))kitsYaml.set(name+".icon",kitIcons.get(name).name());
        }
        try{kitsYaml.save(kitsFile);}catch(IOException e){getLogger().severe("kits.yml save failed: "+e);}
    }
    private ItemStack[] readItems(List<?> list){
        ItemStack[] arr=new ItemStack[list.size()];for(int i=0;i<list.size();i++)if(list.get(i) instanceof ItemStack s)arr[i]=s;
        return arr;
    }
    private void loadKits(){
        kits.clear();kitIcons.clear();
        for(String id:kitsYaml.getKeys(false)){
            kits.put(id,new Kit(readItems(kitsYaml.getList(id+".storage",Collections.emptyList())),
                readItems(kitsYaml.getList(id+".armor",Collections.emptyList())), kitsYaml.getItemStack(id+".offhand")));
            String iconName=kitsYaml.getString(id+".icon");
            if(iconName!=null){
                Material icon=Material.matchMaterial(iconName);
                if(icon!=null && icon.isItem() && !icon.isAir())kitIcons.put(id,icon);
            }
        }
    }
    /** Saves anchors twice: Bukkit Locations plus plain numeric fallback in separate file.
     * This prevents a broken location deserializer/update from erasing arena setup. */
    private File anchorsFile(Arena ar){
        File folder=new File(getDataFolder(),"arena-anchors");folder.mkdirs();
        return new File(folder,ar.id+".yml");
    }
    private void storePlainLocation(YamlConfiguration cfg,String key,Location loc){
        if(loc==null || loc.getWorld()==null)return;
        cfg.set(key+".world",loc.getWorld().getName());
        cfg.set(key+".x",loc.getX());cfg.set(key+".y",loc.getY());cfg.set(key+".z",loc.getZ());
        cfg.set(key+".yaw",loc.getYaw());cfg.set(key+".pitch",loc.getPitch());
    }
    private Location readPlainLocation(YamlConfiguration cfg,String key,World defaultWorld){
        if(!cfg.contains(key+".x") || defaultWorld==null)return null;
        World w=Bukkit.getWorld(cfg.getString(key+".world",defaultWorld.getName()));
        if(w==null || !w.equals(defaultWorld))return null;
        return new Location(w,cfg.getDouble(key+".x"),cfg.getDouble(key+".y"),cfg.getDouble(key+".z"),
            (float)cfg.getDouble(key+".yaw"),(float)cfg.getDouble(key+".pitch"));
    }
    private void saveArenas(){
        for(String k:new HashSet<>(arenaYaml.getKeys(false)))arenaYaml.set(k,null);
        for(Arena a:arenas.values()){
            String path=a.id;arenaYaml.set(path+".world",a.worldName);
            arenaYaml.set(path+".pos1",a.first);arenaYaml.set(path+".pos2",a.second);
            arenaYaml.set(path+".spawns",new ArrayList<>(a.spawns));
            storePlainLocation(arenaYaml,path+".backup-pos1",a.first);
            storePlainLocation(arenaYaml,path+".backup-pos2",a.second);
            YamlConfiguration safe=YamlConfiguration.loadConfiguration(anchorsFile(a));
            // Never delete a previously saved fallback when the live location is missing.
            storePlainLocation(safe,"pos1",a.first);storePlainLocation(safe,"pos2",a.second);
            for(int i=0;i<a.spawns.size();i++)storePlainLocation(safe,"spawn"+(i+1),a.spawns.get(i));
            try{safe.save(anchorsFile(a));}catch(IOException e){getLogger().severe("Anchor backup failed for "+a.id+": "+e);}
        }
        try{
            if(arenaFile.isFile())java.nio.file.Files.copy(arenaFile.toPath(),
                new File(getDataFolder(),"arenas.yml.previous").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            arenaYaml.save(arenaFile);
        }catch(IOException e){getLogger().severe("arenas.yml save failed: "+e);}
    }
    private void recoverSnapshotBounds(Arena a){
        if(a.baseline==null || a.baseline.isEmpty() || (a.first!=null && a.second!=null))return;
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
        int maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(BlockRecord b:a.baseline){
            minX=Math.min(minX,b.x);minY=Math.min(minY,b.y);minZ=Math.min(minZ,b.z);
            maxX=Math.max(maxX,b.x);maxY=Math.max(maxY,b.y);maxZ=Math.max(maxZ,b.z);
        }
        long volume=(long)(maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1);
        if(volume<=0 || volume>getConfig().getLong("max-snapshot-blocks",250000))return;
        // Snapshot contains non-air coordinates only, so these are approximate backup bounds.
        World world=a.world();if(world==null)return;
        if(a.first==null)a.first=new Location(world,minX,minY,minZ);
        if(a.second==null)a.second=new Location(world,maxX,maxY,maxZ);
        getLogger().warning("Recovered missing corners for "+a.id+" from saved snapshot. Inspect bounds before fighting.");
    }
    private void loadArenas(){
        arenas.clear();
        for(String id:arenaYaml.getKeys(false)){
            String worldName=arenaYaml.getString(id+".world","eldritch_"+id);
            World w=loadWorld(worldName);
            if(w==null)continue;
            Arena a=new Arena(id,worldName);
            YamlConfiguration safe=YamlConfiguration.loadConfiguration(anchorsFile(a));
            a.first=arenaYaml.getLocation(id+".pos1");a.second=arenaYaml.getLocation(id+".pos2");
            if(a.first==null)a.first=readPlainLocation(arenaYaml,id+".backup-pos1",w);
            if(a.second==null)a.second=readPlainLocation(arenaYaml,id+".backup-pos2",w);
            if(a.first==null)a.first=readPlainLocation(safe,"pos1",w);
            if(a.second==null)a.second=readPlainLocation(safe,"pos2",w);
            for(Object obj:arenaYaml.getList(id+".spawns",Collections.emptyList()))
                if(obj instanceof Location loc && loc.getWorld()!=null)a.spawns.add(loc);
            for(int i=0;i<16;i++){
                Location saved=readPlainLocation(safe,"spawn"+(i+1),w);
                if(saved!=null){while(a.spawns.size()<=i)a.spawns.add(null);
                    if(a.spawns.get(i)==null)a.spawns.set(i,saved);}
            }
            a.baseline=loadSnapshot(a);
            recoverSnapshotBounds(a);
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
        if(party==null){
            if(page.equals("home")){
                party=new Party(player.getUniqueId());membership.put(player.getUniqueId(),party);
                msg(player,"&aParty created. Use /party invite <player>.");
            }else {msg(player,"&cCreate a party with /party create first.");return;}
        }
        if(party.match!=null){msg(player,"&cYou are currently fighting.");return;}
        Menu menu=new Menu(page,party);
        String title=switch(page){
            case "home" -> "Eldritch - Party Menu";
            case "members" -> "Eldritch - Party Members";
            case "teams" -> "Eldritch - Team Battle";
            case "kits" -> "Eldritch - Select Kit";
            case "arenas" -> "Eldritch - Choose Arena";
            case "rounds" -> "Eldritch - Number of Rounds";
            case "duelSettings" -> "Eldritch - Duel Settings";
            default -> "Eldritch - Match Settings";
        };
        menu.inventory=Bukkit.createInventory(menu,54,title);
        for(int i=0;i<54;i++)menu.inventory.setItem(i,button(Material.GRAY_STAINED_GLASS_PANE," "));
        if(page.equals("home")){
            OfflinePlayer owner=Bukkit.getOfflinePlayer(party.owner);
            ItemStack head=new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta=(SkullMeta)head.getItemMeta();meta.setOwningPlayer(owner);
            meta.setDisplayName(ChatColor.GOLD+"Owner: "+owner.getName());
            meta.setLore(List.of(ChatColor.GRAY+"Members: "+party.teams.size(),ChatColor.YELLOW+"Click to configure party members"));
            head.setItemMeta(meta);action(menu,4,"members",head);
            action(menu,20,"privacy",button(Material.OAK_SIGN,party.open?"&cClose Your Party":"&aOpen Your Party",
                party.open?"&7Click to make invite-only":"&7Click to allow /party join <owner>"));
            action(menu,22,"teams",button(Material.NETHERITE_SWORD,"&6Party Team Battle","&7Teams → Kit → Match Settings"));
            action(menu,24,"broadcast",button(Material.GOAT_HORN,"&dBroadcast Party","&7Send public pink chat invite","&7Party must be open; 30s cooldown"));
            action(menu,31,"leave",button(Material.BARRIER,"&cDisband Party","&7Leader disbands; members leave"));
            if(party.directDuel)action(menu,24,"kits",button(Material.DIAMOND_SWORD,"&eDirect Duel","&7Select a kit"));
        }else if(page.equals("members")){
            int i=10;
            for(UUID memberId:party.teams.keySet()){
                if(i>=44)break;
                OfflinePlayer member=Bukkit.getOfflinePlayer(memberId);
                ItemStack head=new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta=(SkullMeta)head.getItemMeta();meta.setOwningPlayer(member);
                String name=member.getName()==null?memberId.toString():member.getName();
                meta.setDisplayName(ChatColor.LIGHT_PURPLE+name+(memberId.equals(party.owner)?" ★":""));
                meta.setLore(memberId.equals(party.owner)?
                    List.of(ChatColor.GOLD+"Party leader"):
                    List.of(ChatColor.YELLOW+"Left-click: Kick",ChatColor.YELLOW+"Right-click: Transfer leadership"));
                head.setItemMeta(meta);
                action(menu,i++,"member:"+memberId,head);
            }
            action(menu,45,"home",button(Material.BARRIER,"&cBack to Party Menu"));
        }else if(page.equals("teams")){
            for(int i=9;i<27;i++)menu.inventory.setItem(i,button(Material.RED_STAINED_GLASS_PANE,"&cTeam 1"));
            for(int i=27;i<45;i++)menu.inventory.setItem(i,button(Material.BLUE_STAINED_GLASS_PANE,"&9Team 2"));
            int red=9,blue=27,free=9;
            for(Map.Entry<UUID,Integer> e:party.teams.entrySet()){
                if(red>=27||blue>=45||free>=45)break;
                OfflinePlayer op=Bukkit.getOfflinePlayer(e.getKey());
                ItemStack head=new ItemStack(Material.PLAYER_HEAD);SkullMeta meta=(SkullMeta)head.getItemMeta();
                meta.setOwningPlayer(op);meta.setDisplayName(ChatColor.YELLOW+(op.getName()==null?e.getKey().toString():op.getName()));
                meta.setLore(List.of(ChatColor.GRAY+(party.ffa?"Free for all":"Team "+e.getValue()),ChatColor.GREEN+"Click to swap teams"));head.setItemMeta(meta);
                action(menu,party.ffa?free++:(e.getValue()==1?red++:blue++),"swap:"+e.getKey(),head);
            }
            action(menu,45,"home",button(Material.BARRIER,"&cBack to party"));
            action(menu,49,"ffa",button(Material.NETHER_STAR,party.ffa?"&aFFA enabled":"&eTeam Battle","&7Click to toggle FFA/teams"));
            action(menu,53,"kits",button(Material.LIME_CONCRETE,"&aChoose Kit"));
        }else if(page.equals("kits")){
            int i=0;
            for(String id:kits.keySet()){
                if(i>=45)break;
                Material def=id.contains("pot")?Material.SPLASH_POTION:id.contains("mace")?Material.MACE:
                    id.contains("crystal")?Material.END_CRYSTAL:id.contains("uhc")?Material.GOLDEN_APPLE:Material.DIAMOND_SWORD;
                action(menu,i++,"kit:"+id,button(kitIcons.getOrDefault(id,def),"&6"+id,
                    "&7Click to choose and open settings"));
            }
            action(menu,45,"back",button(Material.BARRIER,"&cBack"));
        }else if(page.equals("arenas")){
            action(menu,0,"arena:auto",button(Material.ENDER_EYE,"&eRandom Available Arena",
                "&7Automatically selects a ready, unused arena"));
            action(menu,8,"arena:world",button(Material.GRASS_BLOCK,"&aWorld (Random Coordinates)",
                "&7Safe random positions in the normal world", "&7No region snapshot or regeneration", "&cPlaced blocks remain in the world"));
            int i=1;
            for(Arena a:arenas.values()){
                if(i>=45)break;
                action(menu,i++,"arena:"+a.id,button(Material.GRASS_BLOCK,"&e"+a.id,
                    "&7"+(a.ready()?"Ready":"Not ready: /eb arena inspect "+a.id),a.busy?"&cBusy":"&aSelect arena"));
            }
            action(menu,45,"settings",button(Material.BARRIER,"&cBack to Settings"));
        }else if(page.equals("settings")){
            action(menu,10,"rounds",button(Material.CLOCK,
                "&eRounds: "+(party.infiniteRounds?"INFINITE":party.bestOf),
                "&7Left click: choose number of rounds","&7Shift click: infinite rounds"));
            action(menu,13,"arenaMenu",button(Material.GRASS_BLOCK,"&eArena: "+(party.arena==null?"RANDOM":party.arena),
                "&7Choose random or select an arena"));
            action(menu,16,"duelSettings",button(Material.IRON_BARS,"&6Duel Settings",
                "&7Health, shield stuns, friendly fire","&7Drops and shrinking border"));
            action(menu,45,"kits",button(Material.BARRIER,"&cBack to Kits"));
            action(menu,53,"start",button(Material.LIME_CONCRETE,"&aSTART MATCH",
                party.directDuel?"&7Send duel request to opponent":"&7Teleport everyone into arena",
                "&7Kit: "+(party.kit==null?"none":party.kit)));
        }else if(page.equals("rounds")){
            action(menu,10,"r:-5",button(Material.RED_CONCRETE,"&c-5 Rounds"));
            action(menu,11,"r:-1",button(Material.RED_STAINED_GLASS_PANE,"&c-1 Round"));
            action(menu,13,"r:inf",button(Material.NETHER_STAR,
                party.infiniteRounds?"&aINFINITE ROUNDS":"&eRounds: "+party.bestOf,
                "&7Click to toggle infinite rounds","&7Press /eb stop to end infinite mode"));
            action(menu,15,"r:1",button(Material.LIME_STAINED_GLASS_PANE,"&a+1 Round"));
            action(menu,16,"r:5",button(Material.LIME_CONCRETE,"&a+5 Rounds"));
            int[] presets={1,3,5,7,10,15,20};
            for(int i=0;i<presets.length;i++)action(menu,28+i,"r:set:"+presets[i],
                button(Material.CLOCK,"&e"+presets[i]+" Round"+(presets[i]==1?"":"s")));
            action(menu,45,"settings",button(Material.BARRIER,"&cBack to Settings"));
        }else if(page.equals("duelSettings")){
            action(menu,10,"health",button(Material.GOLDEN_APPLE,"&6Health Indicator: "+onOff(party.healthIndicator),
                "&7Show 20 ❤ (10 hearts) above players' heads", "&7Visible beneath each player's nametag"));
            action(menu,11,"shield",button(Material.SHIELD,"&eShield Stuns: "+onOff(party.shieldStuns),
                "&7Axes disable blocking shields for 5 seconds"));
            action(menu,13,"friendly",button(Material.DIAMOND_SWORD,"&eFriendly Fire: "+onOff(party.friendlyFire)));
            action(menu,14,"drops",button(Material.HOPPER,"&eDeath Drops: "+onOff(party.drops)));
            action(menu,15,"border",button(Material.TNT,"&eShrinking Border: "+onOff(party.border),
                "&7Border stays centered on the fighting arena"));
            action(menu,45,"settings",button(Material.BARRIER,"&cBack to Match Settings"));
        }
        player.openInventory(menu.inventory);
    }
    private String onOff(boolean value){return value?"&aON":"&cOFF";}
    @EventHandler public void inventoryClick(InventoryClickEvent event){
        if(!(event.getWhoClicked() instanceof Player p))return;
        Inventory top=event.getView().getTopInventory();
        if(!(top.getHolder() instanceof Menu menu))return;
        event.setCancelled(true);
        if(event.getClickedInventory()!=top)return;
        String action=menu.actions.get(event.getRawSlot());if(action==null)return;
        if(menu.page.equals("report")){
            if(action.equals("close"))p.closeInventory();return;
        }
        if(menu.page.equals("duelRequest")){
            if(duelRequests.get(p.getUniqueId())!=menu.party){p.closeInventory();msg(p,"&cChallenge expired.");return;}
            p.closeInventory();duelCommand(p,new String[]{action.equals("acceptDuel")?"accept":"deny"});return;
        }
        Party party=membership.get(p.getUniqueId());
        if(party==null||party!=menu.party){p.closeInventory();return;}
        if(!isLeader(p,party)){msg(p,"&cOnly the party leader can configure this menu.");return;}
        if(action.startsWith("member:")){
            UUID member=UUID.fromString(action.substring(7));
            if(member.equals(party.owner)){msg(p,"&eYou cannot kick yourself; use Disband Party.");return;}
            Player target=Bukkit.getPlayer(member);
            if(target==null){msg(p,"&cThat member must be online.");return;}
            partyCommand(p,new String[]{event.isRightClick()?"promote":"kick",target.getName()});
            Bukkit.getScheduler().runTask(this,()->{if(p.isOnline())openMenu(p,"members");});return;
        }
        if(action.equals("privacy")){
            partyCommand(p,new String[]{party.open?"close":"open"});
            Bukkit.getScheduler().runTask(this,()->openMenu(p,"home"));return;
        }
        if(action.equals("broadcast")){
            partyCommand(p,new String[]{"broadcast"});return;
        }
        String next=menu.page;
        if(action.startsWith("swap:")){
            if(!party.ffa){UUID uuid=UUID.fromString(action.substring(5));
                party.teams.put(uuid,party.teams.getOrDefault(uuid,2)==1?2:1);}
            next="teams";
        }else if(action.equals("ffa")){party.ffa=!party.ffa;next="teams";}
        else if(action.startsWith("kit:")){party.kit=action.substring(4);next="settings";}
        else if(action.startsWith("arena:")){
            String id=action.substring(6);
            if(!id.equals("auto") && !id.equals("world") && !arenas.containsKey(id))return;
            party.arena=id.equals("auto")?null:id;next="settings";
        }else if(action.startsWith("r:")){
            if(action.equals("r:inf"))party.infiniteRounds=!party.infiniteRounds;
            else if(action.startsWith("r:set:")){
                party.bestOf=Integer.parseInt(action.substring(6));party.infiniteRounds=false;
            }else{
                party.bestOf=Math.max(1,Math.min(99,party.bestOf+Integer.parseInt(action.substring(2))));
                party.infiniteRounds=false;
            }
            next="rounds";
        }else switch(action){
            case "home","members","teams","kits","settings","duelSettings","arenaMenu" ->
                next=action.equals("arenaMenu")?"arenas":action;
            case "back" -> next=party.directDuel?"settings":"teams";
            case "rounds" -> {
                if(event.isShiftClick()){party.infiniteRounds=!party.infiniteRounds;next="settings";}
                else next="rounds";
            }
            case "health" -> party.healthIndicator=!party.healthIndicator;
            case "shield" -> party.shieldStuns=!party.shieldStuns;
            case "friendly" -> party.friendlyFire=!party.friendlyFire;
            case "drops" -> party.drops=!party.drops;
            case "border" -> party.border=!party.border;
            case "leave" -> {p.closeInventory();partyCommand(p,new String[]{"leave"});return;}
            case "start" -> {
                p.closeInventory();
                if(party.directDuel)sendDuelChallenge(p,party);else startMatch(p,party);
                return;
            }
        }
        String destination=next;
        Bukkit.getScheduler().runTask(this,()->{if(p.isOnline())openMenu(p,destination);});
    }
    @EventHandler public void inventoryDrag(InventoryDragEvent event){
        if(event.getView().getTopInventory().getHolder() instanceof Menu)event.setCancelled(true);
    }
    private Arena resolveArena(Party party){
        if(party.arena!=null){
            Arena selected=party.arena.equals("world")?worldArena:arenas.get(party.arena);
            return selected!=null && selected.ready() && !selected.busy?selected:null;
        }
        List<Arena> ready=new ArrayList<>();
        for(Arena a:arenas.values())if(a.ready()&&!a.busy)ready.add(a);
        if(getConfig().getBoolean("world-duels.include-in-random",true)
            && worldArena!=null && worldArena.ready() && !worldArena.busy)ready.add(worldArena);
        return ready.isEmpty()?null:ready.get(ThreadLocalRandom.current().nextInt(ready.size()));
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
        Arena arena=resolveArena(party);
        if(arena==null){msg(owner,"&cNo available ready arena. Check /eb arena inspect <name>.");return;}
        if(!arena.worldMode() && (arena.spawns.size()<2 || arena.spawns.get(0)==null || arena.spawns.get(1)==null)){
            msg(owner,"&cSet Team 1/2 spawns with /eb arena spawn "+arena.id+" 1 or 2.");return;
        }
        Map<UUID,Location> initial=new LinkedHashMap<>();
        if(arena.worldMode()){
            initial=chooseWorldSpawns(arena,party);
            if(initial==null){msg(owner,"&cCould not find enough safe ground in the configured world. Try again or select a custom arena.");return;}
        }else{
            for(Map.Entry<UUID,Integer> entry:party.teams.entrySet()){
                int team=party.ffa?1:entry.getValue();
                Location anchor=arena.spawns.get(team==2?1:0);
                if(anchor==null || !anchor.getWorld().equals(arena.world())){
                    msg(owner,"&cSaved spawn points are missing/incorrect. Use /eb arena spawn "+arena.id+" 1/2.");return;
                }
                initial.put(entry.getKey(),anchor.clone());
            }
        }
        arena.busy=true;
        Match m=new Match(party,arena,kits.get(party.kit));party.match=m;
        if(arena.worldMode())m.worldSpawns.putAll(initial);
        setupBorder(m); // Do not modify the main world's shared border for World duels.
        for(UUID u:party.teams.keySet()){
            Player p=Bukkit.getPlayer(u);
            PlayerBackup backup=new PlayerBackup(p);m.backups.put(u,backup);
            saveRecovery(backup);playing.put(u,m);
            p.closeInventory();p.setGameMode(GameMode.ADVENTURE);
            p.setInvulnerable(false);p.setFallDistance(0);p.setGravity(false);
            p.teleport(initial.get(u)); // Immediate arena teleport while the baseline is being restored.
            p.sendTitle(ChatColor.GOLD+"MATCH FOUND",ChatColor.YELLOW+party.kit+" • "+arena.id,10,45,10);
        }
        partyMsg(party,"&6MATCH FOUND! &e"+party.kit+" | "+(party.ffa?"FFA":red+"v"+blue)
            +" | "+(party.infiniteRounds?"Infinite rounds":party.bestOf+" rounds"));
        beginRound(m);
    }
    /** Ground-based World duels. Limited searches around one randomly chosen region avoid long teleports.
     * This mode intentionally does NOT save/reset the overworld or modify its world border. */
    private Map<UUID,Location> chooseWorldSpawns(Arena arena,Party party){
        World w=arena.world();if(w==null)return null;
        ThreadLocalRandom rng=ThreadLocalRandom.current();
        int min=Math.max(256,getConfig().getInt("world-duels.minimum-distance",1200));
        int max=Math.max(min+128,getConfig().getInt("world-duels.maximum-distance",4000));
        for(int region=0;region<8;region++){
            int centerX=(rng.nextBoolean()?1:-1)*rng.nextInt(min,max);
            int centerZ=(rng.nextBoolean()?1:-1)*rng.nextInt(min,max);
            Map<UUID,Location> result=new LinkedHashMap<>();
            List<Location> used=new ArrayList<>();
            boolean found=true;int playerIndex=0;
            for(Map.Entry<UUID,Integer> entry:party.teams.entrySet()){
                int team=party.ffa?playerIndex:entry.getValue()-1;
                // Separate teams, and spread FFA participants around the center.
                double angle=party.ffa?(playerIndex*2.0*Math.PI/party.teams.size()):team*Math.PI;
                int ring=party.ffa?Math.max(14,party.teams.size()*3):18;
                int baseX=centerX+(int)Math.round(ring*Math.cos(angle));
                int baseZ=centerZ+(int)Math.round(ring*Math.sin(angle));
                Location picked=null;
                for(int trial=0;trial<12;trial++){
                    int x=baseX+rng.nextInt(-6,7),z=baseZ+rng.nextInt(-6,7);
                    Location candidate=safeWorldGround(w,x,z);
                    if(candidate!=null && separated(candidate,used,2.5)){
                        picked=candidate;break;
                    }
                }
                if(picked==null){found=false;break;}
                picked.setYaw((float)Math.toDegrees(Math.atan2(centerZ-picked.getZ(),centerX-picked.getX()))-90f);
                result.put(entry.getKey(),picked);used.add(picked);playerIndex++;
            }
            if(found)return result;
        }
        return null;
    }
    private Location safeWorldGround(World w,int x,int z){
        // getHighestBlockYAt loads/generates the chunk; only call synchronously from the server thread.
        int y=w.getHighestBlockYAt(x,z)+1;
        if(y<=w.getMinHeight()+2 || y>=w.getMaxHeight()-2)return null;
        Material floor=w.getBlockAt(x,y-1,z).getType();
        if(!floor.isSolid() || floor.name().contains("LEAVES") || floor==Material.MAGMA_BLOCK ||
           floor==Material.CACTUS || floor==Material.CAMPFIRE || floor==Material.SOUL_CAMPFIRE ||
           floor==Material.POWDER_SNOW || floor==Material.BEDROCK)return null;
        if(!w.getBlockAt(x,y,z).isPassable() || !w.getBlockAt(x,y+1,z).isPassable())return null;
        return new Location(w,x+0.5,y,z+0.5);
    }
    private void setupBorder(Match m){
        if(m.arena.worldMode())return; // A world border affects EVERY player in the overworld.
        WorldBorder border=m.arena.world().getWorldBorder();
        border.reset();
        double centerX=(m.arena.minX()+m.arena.maxX()+1)/2.0;
        double centerZ=(m.arena.minZ()+m.arena.maxZ()+1)/2.0;
        border.setCenter(centerX,centerZ);
        double size=Math.max(100.0,Math.max(m.arena.maxX()-m.arena.minX()+1,
            m.arena.maxZ()-m.arena.minZ()+1)+40.0);
        border.setSize(size);
        border.setDamageAmount(0.0); // No border damage during teleport/countdown.
        border.setWarningDistance(6);
    }
    private void beginRound(Match m){
        if(m.ending)return;
        m.transitioning=true;
        for(BukkitTask task:m.tasks)task.cancel();m.tasks.clear();
        setupBorder(m);
        partyMsg(m.party,"&ePreparing saved arena blocks...");
        resetArena(m.arena,m.placedBlocks,()->{
            if(m.ending)return;
            m.round++;m.eliminated.clear();m.lastInventories.clear();
            m.placedBlocks.clear(); // Reset has removed all placed blocks from the previous round.
            Map<UUID,Location> positions=chooseRoundSpawns(m);
            if(positions==null){
                partyMsg(m.party,"&cCannot find enough safe spawn locations inside the saved arena."
                    +" &7Move team spawns onto the fighting floor and check /eb arena inspect.");
                abortMatch(m);return;
            }
            for(UUID uuid:m.party.teams.keySet()){
                Player p=Bukkit.getPlayer(uuid);
                if(p==null){m.eliminated.add(uuid);continue;}
                PlayerInventory inv=p.getInventory();inv.clear();
                inv.setStorageContents(cloneItems(m.kit.storage));
                inv.setArmorContents(cloneItems(m.kit.armor));
                inv.setItemInOffHand(m.kit.offhand==null?new ItemStack(Material.AIR):m.kit.offhand.clone());
                p.setGameMode(GameMode.SURVIVAL);p.setFoodLevel(20);p.setSaturation(20);
                p.setFireTicks(0);p.setFallDistance(0);
                p.setHealth(p.getAttribute(Attribute.MAX_HEALTH)==null?20.0:
                    p.getAttribute(Attribute.MAX_HEALTH).getValue());
                p.teleport(positions.get(uuid));p.setGravity(true);
            }
            applyHealthIndicator(m);
            partyMsg(m.party,"&eRound "+m.round+" / "+(m.party.infiniteRounds?"∞":m.party.bestOf)+" begins soon!");
            BukkitTask countdown=new BukkitRunnable(){
                int seconds=5;
                @Override public void run(){
                    if(m.ending){cancel();return;}
                    if(seconds<=0){
                        cancel();m.transitioning=false;
                        for(UUID u:m.party.teams.keySet()){
                            Player p=Bukkit.getPlayer(u);
                            if(p!=null)p.sendTitle(ChatColor.GREEN+"FIGHT!",ChatColor.YELLOW+"Round "+m.round,0,25,10);
                        }
                        if(!m.arena.worldMode() && m.party.border && getConfig().getBoolean("border.enabled",true)){
                            WorldBorder b=m.arena.world().getWorldBorder();
                            b.setDamageAmount(0.2);
                            int delay=Math.max(1,getConfig().getInt("border.delay-seconds",480));
                            BukkitTask borderTask=Bukkit.getScheduler().runTaskLater(EldritchBattles.this,()->{
                                if(!m.ending&&!m.transitioning){
                                    b.setSize(Math.max(1,getConfig().getDouble("border.final-size",10)),
                                        Math.max(1,getConfig().getLong("border.shrink-seconds",180)));
                                    partyMsg(m.party,"&cWorld border is shrinking!");
                                }
                            },20L*delay);m.tasks.add(borderTask);
                        }
                        checkRound(m);return;
                    }
                    for(UUID u:m.party.teams.keySet()){
                        Player p=Bukkit.getPlayer(u);
                        if(p!=null)p.sendTitle(ChatColor.GOLD+""+seconds,
                            ChatColor.YELLOW+"Round "+m.round+" of "+(m.party.infiniteRounds?"∞":m.party.bestOf),0,22,0);
                    }
                    seconds--;
                }
            }.runTaskTimer(this,0L,20L);
            m.tasks.add(countdown);
        });
    }
    private void applyHealthIndicator(Match match){
        if(!match.party.healthIndicator)return;
        ScoreboardManager sm=Bukkit.getScoreboardManager();
        if(sm==null)return;
        try{
            Scoreboard board=sm.getNewScoreboard();
            // Explicitly managed scores avoid the vanilla HEALTH criterion initialization issue.
            Objective health=board.registerNewObjective("eb_health",Criteria.DUMMY,
                Component.text("❤",NamedTextColor.RED));
            health.setDisplaySlot(DisplaySlot.BELOW_NAME);
            match.healthBoard=board;
            // Update after health changes and keep the board active if another plugin replaces it.
            BukkitTask updater=new BukkitRunnable(){
                @Override public void run(){
                    if(match.ending || match.healthBoard!=board){cancel();return;}
                    for(UUID u:match.party.teams.keySet()){
                        Player participant=Bukkit.getPlayer(u);
                        if(participant==null || !participant.isOnline())continue;
                        // Minecraft health uses 2 HP per heart: 20 HP = 10 full hearts.
                        // Only the numeric HP is shown in the overhead BELOW_NAME label.
                        int hp=(int)Math.ceil(Math.max(0,participant.getHealth()));
                        health.getScore(participant.getName()).setScore(hp);
                        if(participant.getScoreboard()!=board)participant.setScoreboard(board);
                    }
                }
            }.runTaskTimer(this,0L,5L);
            match.tasks.add(updater);
        }catch(Exception ex){getLogger().warning("Could not display health indicator: "+ex.getMessage());}
    }
    /** Only choose level, solid, non-hazardous floor with space for the player and their head. */
    private boolean isSafeArenaSpawn(Arena a,Location at){
        if(at==null || !a.inside(at) || at.getWorld()==null)return false;
        int x=at.getBlockX(),y=at.getBlockY(),z=at.getBlockZ();
        World world=at.getWorld();
        if(y<=world.getMinHeight() || y+1>=world.getMaxHeight())return false;
        Material floor=world.getBlockAt(x,y-1,z).getType();
        if(!floor.isSolid() || floor==Material.MAGMA_BLOCK || floor==Material.CACTUS ||
            floor==Material.CAMPFIRE || floor==Material.SOUL_CAMPFIRE)return false;
        return world.getBlockAt(x,y,z).getType().isAir() && world.getBlockAt(x,y+1,z).getType().isAir();
    }
    private boolean separated(Location candidate,List<Location> used,double minDistance){
        for(Location other:used){
            if(other.getWorld()==candidate.getWorld() && other.distanceSquared(candidate)<minDistance*minDistance)
                return false;
        }
        return true;
    }
    /** Spawn teammates near their own anchor, not at exactly the same block. */
    private Location nearTeamAnchor(Arena a,Location anchor,List<Location> used){
        if(isSafeArenaSpawn(a,anchor) && separated(anchor,used,2.0))return anchor.clone();
        ThreadLocalRandom rng=ThreadLocalRandom.current();
        for(int radius=2;radius<=8;radius++){
            for(int attempt=0;attempt<35;attempt++){
                double angle=rng.nextDouble(Math.PI*2);
                int x=anchor.getBlockX()+(int)Math.round(radius*Math.cos(angle));
                int z=anchor.getBlockZ()+(int)Math.round(radius*Math.sin(angle));
                for(int yOffset : new int[]{0,1,-1,2,-2}){
                    Location candidate=new Location(anchor.getWorld(),x+0.5,anchor.getBlockY()+yOffset,z+0.5,
                        anchor.getYaw(),anchor.getPitch());
                    if(isSafeArenaSpawn(a,candidate) && separated(candidate,used,2.0))return candidate;
                }
            }
        }
        return null;
    }
    /** Pick independent FFA floor positions from the rectangular arena selection, not from empty void. */
    private Location randomFfaSpawn(Arena a,Location reference,List<Location> used){
        ThreadLocalRandom rng=ThreadLocalRandom.current();
        int marginX=(a.maxX()-a.minX())>10?2:0;
        int marginZ=(a.maxZ()-a.minZ())>10?2:0;
        int minX=a.minX()+marginX,maxX=a.maxX()-marginX;
        int minZ=a.minZ()+marginZ,maxZ=a.maxZ()-marginZ;
        // Keep FFA players at approximately the same floor height as the team spawn markers.
        // This avoids placing players on castle rooftops or unrelated structures.
        for(double minDistance:new double[]{8.0,5.0,2.0}){
            for(int attempt=0;attempt<500;attempt++){
                int x=rng.nextInt(minX,maxX+1),z=rng.nextInt(minZ,maxZ+1);
                for(int offset:new int[]{0,1,-1,2,-2}){
                    Location candidate=new Location(reference.getWorld(),x+0.5,reference.getBlockY()+offset,z+0.5,
                        rng.nextFloat()*360f,0f);
                    if(isSafeArenaSpawn(a,candidate) && separated(candidate,used,minDistance))return candidate;
                }
            }
        }
        return null;
    }
    private Map<UUID,Location> chooseRoundSpawns(Match match){
        Arena arena=match.arena;
        if(arena.worldMode())return new LinkedHashMap<>(match.worldSpawns);
        Map<UUID,Location> chosen=new LinkedHashMap<>();
        List<Location> used=new ArrayList<>();
        for(Map.Entry<UUID,Integer> e:match.party.teams.entrySet()){
            Location selected;
            if(match.party.ffa){
                selected=randomFfaSpawn(arena,arena.spawns.get(0),used);
            }else{
                int team=e.getValue();
                if(team!=1 && team!=2)return null;
                selected=nearTeamAnchor(arena,arena.spawns.get(team-1),used);
            }
            if(selected==null)return null;
            chosen.put(e.getKey(),selected);used.add(selected);
        }
        return chosen;
    }
    private void abortMatch(Match m){
        m.ending=true;
        for(BukkitTask task:m.tasks)task.cancel();m.tasks.clear();
        for(UUID u:m.party.teams.keySet()){
            playing.remove(u);
            Player p=Bukkit.getPlayer(u);PlayerBackup b=m.backups.get(u);
            if(p!=null && b!=null && !p.isDead()){p.setGravity(true);restore(p,b);}
        }
        m.placedBlocks.clear();
        m.party.match=null;m.arena.busy=false;
        if(!m.arena.worldMode())m.arena.world().getWorldBorder().reset();
        cleanupDuelParty(m.party);
    }
    private Arena arenaForWorld(World world){
        for(Arena arena:arenas.values())if(arena.worldName.equals(world.getName()))return arena;
        return null;
    }
    private boolean canEditArenaOutsideMatch(Player player,Arena arena){
        // Builders can still edit the original map in Creative mode while no fight is running.
        return !arena.busy && player.getGameMode()==GameMode.CREATIVE
            && player.hasPermission("eldritch.admin");
    }
    @EventHandler public void onBreak(BlockBreakEvent event){
        Arena arena=arenaForWorld(event.getBlock().getWorld());
        if(arena==null)return;
        Match match=playing.get(event.getPlayer().getUniqueId());
        if(match!=null && match.arena==arena && !match.ending && !match.transitioning
            && match.placedBlocks.contains(BlockKey.of(event.getBlock())))return;
        if(match==null && canEditArenaOutsideMatch(event.getPlayer(),arena))return;
        // Everything else is part of the protected map, including towers outside the snapshot cuboid.
        event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void afterBreak(BlockBreakEvent event){
        Match m=playing.get(event.getPlayer().getUniqueId());
        if(m!=null && m.arena.world()==event.getBlock().getWorld())
            m.placedBlocks.remove(BlockKey.of(event.getBlock()));
    }
    @EventHandler public void onPlace(BlockPlaceEvent event){
        Match m=playing.get(event.getPlayer().getUniqueId());
        if(m!=null && (m.ending || m.transitioning || !m.arena.inside(event.getBlockPlaced().getLocation())))
            event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void afterPlace(BlockPlaceEvent event){
        Match m=playing.get(event.getPlayer().getUniqueId());
        if(m!=null && !m.ending && !m.transitioning && m.arena.inside(event.getBlockPlaced().getLocation()))
            m.placedBlocks.add(BlockKey.of(event.getBlockPlaced()));
    }
    private void protectMapFromExplosion(World world,List<Block> blocks){
        Arena arena=arenaForWorld(world);
        if(arena==null)return;
        Match match=arenaMatch(arena);
        blocks.removeIf(block->match==null || !match.placedBlocks.contains(BlockKey.of(block)));
        // A destroyed placed block must no longer be considered breakable after an explosion.
        if(match!=null)for(Block block:blocks)match.placedBlocks.remove(BlockKey.of(block));
    }
    private Match arenaMatch(Arena arena){
        for(Match match:playing.values())if(match.arena==arena && !match.ending && !match.transitioning)
            return match;
        return null;
    }
    @EventHandler public void onEntityExplode(EntityExplodeEvent event){
        protectMapFromExplosion(event.getLocation().getWorld(),event.blockList());
    }
    @EventHandler public void onBlockExplode(BlockExplodeEvent event){
        protectMapFromExplosion(event.getBlock().getWorld(),event.blockList());
    }
    @EventHandler public void onBlockBurn(BlockBurnEvent event){
        Arena arena=arenaForWorld(event.getBlock().getWorld());
        if(arena==null)return;
        Match match=arenaMatch(arena);
        if(match==null || !match.placedBlocks.contains(BlockKey.of(event.getBlock())))event.setCancelled(true);
    }
    @EventHandler public void onBlockFade(BlockFadeEvent event){
        Arena arena=arenaForWorld(event.getBlock().getWorld());
        if(arena==null)return;
        Match match=arenaMatch(arena);
        if(match==null || !match.placedBlocks.contains(BlockKey.of(event.getBlock())))event.setCancelled(true);
    }
    @EventHandler public void onAnyDamage(EntityDamageEvent event){
        if(!(event.getEntity() instanceof Player p))return;
        Match m=playing.get(p.getUniqueId());
        if(m!=null && (m.ending || m.transitioning || m.eliminated.contains(p.getUniqueId())))
            event.setCancelled(true);
    }
    @EventHandler public void damage(EntityDamageByEntityEvent event){
        if(!(event.getEntity() instanceof Player hurt))return;
        Match m=playing.get(hurt.getUniqueId());if(m==null)return;
        if(m.ending||m.transitioning||m.eliminated.contains(hurt.getUniqueId())){event.setCancelled(true);return;}
        Entity source=event.getDamager();
        Player attacker=source instanceof Player p?p:source instanceof Projectile projectile&&projectile.getShooter() instanceof Player p?p:null;
        if(attacker!=null&&m.party.teams.containsKey(attacker.getUniqueId())&& !m.party.ffa
            && m.side(attacker.getUniqueId()).equals(m.side(hurt.getUniqueId()))&&!m.party.friendlyFire){
            event.setCancelled(true);return;
        }
    }
    /** Paper exposes the shield-disable event before the game applies the cooldown.
     * Updating that event fixes a race where setCooldown() in damage handlers was overwritten. */
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onShieldDisable(PlayerShieldDisableEvent event){
        Player defender=event.getPlayer();
        Match match=playing.get(defender.getUniqueId());
        if(match==null || match.ending || match.transitioning)return;
        if(!(event.getDamager() instanceof Player attacker))return;
        if(playing.get(attacker.getUniqueId())!=match)return;
        if(!match.party.ffa && match.side(attacker.getUniqueId()).equals(match.side(defender.getUniqueId()))
            && !match.party.friendlyFire){
            event.setCancelled(true);
            return;
        }
        // 100 ticks = 5 seconds. When disabled, axe hits cannot stun match participants.
        if(match.party.shieldStuns)event.setCooldown(100);
        else event.setCancelled(true);
    }
    private Kit currentKit(Player p){return new Kit(p.getInventory().getStorageContents(),
        p.getInventory().getArmorContents(),p.getInventory().getItemInOffHand());}
    @EventHandler public void onDeath(PlayerDeathEvent event){
        Player p=event.getEntity();Match m=playing.get(p.getUniqueId());if(m==null)return;
        m.lastInventories.put(p.getUniqueId(),currentKit(p));
        m.deaths.merge(p.getUniqueId(),1,Integer::sum);
        Player killer=p.getKiller();
        if(killer!=null && m.party.teams.containsKey(killer.getUniqueId()) &&
            !killer.getUniqueId().equals(p.getUniqueId()))m.kills.merge(killer.getUniqueId(),1,Integer::sum);
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
        Location spectatorSpawn=m.arena.worldMode()?m.worldSpawns.get(player.getUniqueId()):
            (!m.arena.spawns.isEmpty()?m.arena.spawns.get(0):null);
        event.setRespawnLocation(spectatorSpawn==null?Bukkit.getWorlds().get(0).getSpawnLocation():spectatorSpawn);
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
        Party challenge=membership.get(p.getUniqueId());
        if(challenge!=null&&challenge.directDuel&&challenge.match==null)
            cancelDuel(challenge,"&eDuel cancelled because a player disconnected.");
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
        if(alive.isEmpty()){
            boolean anyoneOnline=false;
            for(UUID u:m.party.teams.keySet())if(Bukkit.getPlayer(u)!=null){anyoneOnline=true;break;}
            if(!anyoneOnline){finishMatch(m,"draw");return;}
            partyMsg(m.party,"&eRound drawn; replaying.");m.round--;waitAndNext(m);return;
        }
        String winner=alive.iterator().next();int score=m.wins.merge(winner,1,Integer::sum);
        String name=m.party.ffa ? Bukkit.getOfflinePlayer(UUID.fromString(winner)).getName() :
            "Team "+winner.substring(4);
        partyMsg(m.party,"&6"+name+" &awon round "+m.round+"! ("+score+" round wins)");
        if(!m.party.infiniteRounds && m.round>=m.party.bestOf){
            finishMatch(m,winningSide(m));return;
        }
        waitAndNext(m);
    }
    private String winningSide(Match m){
        String winner=null;int top=-1;boolean tied=false;
        for(UUID u:m.party.teams.keySet()){
            String side=m.side(u);int points=m.wins.getOrDefault(side,0);
            if(points>top){winner=side;top=points;tied=false;}
            else if(points==top && !side.equals(winner))tied=true;
        }
        return top<=0||tied?"draw":winner;
    }
    private String winnerName(Match m,String side){
        if(side==null || side.equals("draw"))return "DRAW";
        if(!m.party.ffa)return side.startsWith("team")?"Team "+side.substring(4):side;
        try {return Bukkit.getOfflinePlayer(UUID.fromString(side)).getName();}
        catch(Exception ignored){return side;}
    }
    private void waitAndNext(Match m){
        for(UUID u:m.party.teams.keySet()){
            Player p=Bukkit.getPlayer(u);
            if(p!=null){p.setGameMode(GameMode.ADVENTURE);p.setGravity(false);
                Location rest=m.arena.worldMode()?m.worldSpawns.get(u):m.arena.spawns.get(0);
                if(rest!=null)p.teleport(rest); }
        }
        Bukkit.getScheduler().runTaskLater(this,()->{if(!m.ending)beginRound(m);},60L);
    }
    private void cleanupDuelParty(Party party){
        if(!party.directDuel)return;
        duelTargets.remove(party.owner);
        duelRequests.entrySet().removeIf(e->e.getValue()==party);
        for(UUID u:party.teams.keySet())membership.remove(u,party);
    }
    private void finishMatch(Match m,String winner){
        if(m.ending)return;m.ending=true;
        for(BukkitTask task:m.tasks)task.cancel();m.tasks.clear();
        String winnerLabel=winnerName(m,winner);
        for(UUID u:m.party.teams.keySet()){
            Player p=Bukkit.getPlayer(u);
            if(p!=null && !p.isDead() && !m.lastInventories.containsKey(u))
                m.lastInventories.put(u,currentKit(p));
        }
        MatchReport report=new MatchReport(m,winnerLabel);
        reports.put(report.id,report);
        while(reports.size()>30){String oldest=reports.keySet().iterator().next();reports.remove(oldest);}
        for(UUID u:m.party.teams.keySet()){
            Player p=Bukkit.getPlayer(u);if(p==null)continue;
            boolean victory=!winner.equals("draw") && winner.equals(m.side(u));
            p.sendTitle(victory?ChatColor.GOLD+"VICTORY":ChatColor.RED+(winner.equals("draw")?"DRAW":"DEFEAT"),
                ChatColor.YELLOW+"MATCH ENDED",5,55,15);
            msg(p,"&6✦ MATCH REPORT ✦ &7Kit: &e"+report.kitName+" &7| Arena: &e"+report.arenaName);
            msg(p,"&aWinner: &f"+winnerLabel+" &7| Round wins: &f"+report.score);
            for(UUID member:m.party.teams.keySet()){
                String name=Bukkit.getOfflinePlayer(member).getName();
                String label="  "+(name==null?member.toString():name)+" [CLICK]"+
                    " - "+report.kills.getOrDefault(member,0)+" kills / "+report.deaths.getOrDefault(member,0)+" deaths";
                Component line=Component.text(label,NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand("/eb report "+report.id+" "+member))
                    .hoverEvent(HoverEvent.showText(Component.text("Inspect inventory, armor, durability and resources")));
                p.sendMessage(line);
            }
            msg(p,"&7Click a name above to inspect their final fight inventory.");
        }
        for(UUID u:m.party.teams.keySet()){
            playing.remove(u);Player p=Bukkit.getPlayer(u);PlayerBackup backup=m.backups.get(u);
            if(p!=null && backup!=null && !p.isDead()){p.setGravity(true);restore(p,backup);}
        }
        m.party.match=null;
        cleanupDuelParty(m.party);
        if(!m.arena.worldMode())m.arena.world().getWorldBorder().reset();
        resetArena(m.arena,m.placedBlocks,()->{m.placedBlocks.clear();m.arena.busy=false;
            getLogger().info("Arena "+m.arena.id+" reset after match.");});
    }
    private void reportCommand(Player p,String[] args){
        if(args.length<2){msg(p,"&cClick a username in a recent match report to inspect their items.");return;}
        MatchReport report=reports.get(args[0]);
        if(report==null){msg(p,"&cThat match report has expired or the server restarted.");return;}
        UUID player;
        try{player=UUID.fromString(args[1]);}catch(IllegalArgumentException ex){msg(p,"&cUnknown player.");return;}
        Kit kit=report.inventories.get(player);
        if(kit==null){msg(p,"&cNo inventory was captured for that player.");return;}
        Menu menu=new Menu("report",null);
        menu.inventory=Bukkit.createInventory(menu,54,"Eldritch - Inventory Report");
        for(int i=0;i<36&&i<kit.storage.length;i++){
            ItemStack stack=kit.storage[i];
            if(stack!=null)menu.inventory.setItem(i,annotatedItem(stack));
        }
        for(int i=0;i<4&&i<kit.armor.length;i++){
            ItemStack stack=kit.armor[i];
            if(stack!=null)menu.inventory.setItem(36+i,annotatedItem(stack));
        }
        if(kit.offhand!=null)menu.inventory.setItem(40,annotatedItem(kit.offhand));
        String name=Bukkit.getOfflinePlayer(player).getName();
        List<String> resourceLines=new ArrayList<>();
        resourceLines.add("&7Player: "+name);
        resourceLines.add("&7Kills: "+report.kills.getOrDefault(player,0)+" | Deaths: "+report.deaths.getOrDefault(player,0));
        Map<Material,Integer> counts=new LinkedHashMap<>();
        for(ItemStack st:kit.storage)if(st!=null && !st.getType().isAir()){
            Material material=st.getType();
            if(material.name().contains("POTION") || material.name().contains("APPLE") ||
                material.name().contains("CRYSTAL") || material==Material.TOTEM_OF_UNDYING ||
                material==Material.EXPERIENCE_BOTTLE || material==Material.ENDER_PEARL ||
                material==Material.ARROW || material==Material.OBSIDIAN || material==Material.COBWEB ||
                material==Material.TNT || material==Material.GOLDEN_CARROT)
                counts.merge(material,st.getAmount(),Integer::sum);
        }
        for(Map.Entry<Material,Integer> e:counts.entrySet()){
            if(resourceLines.size()>=25)break;
            resourceLines.add("&e"+e.getKey().name().toLowerCase(Locale.ROOT)+" &7x"+e.getValue());
        }
        menu.inventory.setItem(42,button(Material.BOOK,"&6Match resources",resourceLines.toArray(String[]::new)));
        action(menu,49,"close",button(Material.BARRIER,"&cClose Report"));
        p.openInventory(menu.inventory);
    }
    private ItemStack annotatedItem(ItemStack original){
        ItemStack copy=original.clone();ItemMeta meta=copy.getItemMeta();
        if(meta instanceof Damageable damageable && copy.getType().getMaxDurability()>0){
            int max=copy.getType().getMaxDurability();int left=Math.max(0,max-damageable.getDamage());
            List<String> lore=meta.hasLore()?new ArrayList<>(meta.getLore()):new ArrayList<>();
            lore.add(ChatColor.GRAY+"Durability: "+left+" / "+max);
            meta.setLore(lore);copy.setItemMeta(meta);
        }
        return copy;
    }
    private void restore(Player p,PlayerBackup b){
        p.getInventory().clear();p.getInventory().setStorageContents(cloneItems(b.inventory.storage));
        p.getInventory().setArmorContents(cloneItems(b.inventory.armor));
        p.getInventory().setItemInOffHand(b.inventory.offhand==null?new ItemStack(Material.AIR):b.inventory.offhand.clone());
        p.setGameMode(b.gamemode);p.setFoodLevel(b.food);p.setFireTicks(0);p.setGravity(true);
        if(b.scoreboard!=null)p.setScoreboard(b.scoreboard);
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
        p.setGravity(true);
        if(loc!=null&&loc.getWorld()!=null)p.teleport(loc);
        recoveryYaml.set(key,null);flushRecovery();msg(p,"&aYour inventory was restored after the previous battle.");
    }
}
