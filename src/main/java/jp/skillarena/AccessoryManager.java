package jp.skillarena;

import jp.skillarena.boss.VargasBoss;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.util.*;

/** Small, instanced PvE-only equipment bonuses. No vanilla armor slots are consumed. */
public final class AccessoryManager implements Listener {
  public static final int ATTACK=0, DEFENSE=1, MP_REGEN=2, STAMINA_REGEN=3, ULT=4, SPEED=5;
  private static final String TITLE="§8SkillArena | Accessories";
  private static final String[] NAMES={"剣帝の紋章","耐衝撃チップ","魔力コンデンサ","循環補助装置","共鳴結晶","機動制御核"};
  private static final Material[] ICONS={Material.NETHERITE_SCRAP,Material.IRON_NUGGET,Material.LAPIS_LAZULI,Material.REDSTONE,Material.AMETHYST_SHARD,Material.ENDER_PEARL};
  private static final String[] DESCS={"与ダメージ +3%","被ダメージ -3%","MP自然回復 +0.2/更新","通常時スタミナ回復 +6%","ウルト獲得量 +5%","移動速度 +2%"};
  private final JavaPlugin plugin;
  private final Map<UUID,Profile> profiles=new HashMap<>();
  private final Set<UUID> dungeon=new HashSet<>();
  private static final class Profile {Set<Integer> owned=new HashSet<>();int[] slots={-1,-1,-1,-1};}
  public AccessoryManager(JavaPlugin plugin){this.plugin=plugin;Bukkit.getPluginManager().registerEvents(this,plugin);}
  private Profile profile(Player p){return profiles.computeIfAbsent(p.getUniqueId(),k->new Profile());}
  private File file(Player p){return new File(new File(plugin.getDataFolder(),"players"),p.getUniqueId()+".yml");}
  public void load(Player p){Profile x=new Profile();YamlConfiguration y=YamlConfiguration.loadConfiguration(file(p));for(int i:y.getIntegerList("accessories.owned"))if(i>=0&&i<NAMES.length)x.owned.add(i);for(int i=0;i<4;i++){int id=y.getInt("accessories.slots."+i,-1);if(x.owned.contains(id)){boolean duplicate=false;for(int j=0;j<i;j++)if(x.slots[j]==id)duplicate=true;if(!duplicate)x.slots[i]=id;}}profiles.put(p.getUniqueId(),x);}
  public void save(Player p){File f=file(p);YamlConfiguration y=YamlConfiguration.loadConfiguration(f);Profile x=profile(p);List<Integer> ids=new ArrayList<>(x.owned);Collections.sort(ids);y.set("accessories.owned",ids);for(int i=0;i<4;i++)y.set("accessories.slots."+i,x.slots[i]);try{y.save(f);}catch(IOException ex){plugin.getLogger().warning("Accessory save: "+ex.getMessage());}}
  public void setDungeonMode(Player p,boolean active){if(active)dungeon.add(p.getUniqueId());else dungeon.remove(p.getUniqueId());}
  public boolean isActive(Player p,VargasBoss boss){return dungeon.contains(p.getUniqueId())||(boss!=null&&boss.isParticipant(p));}
  public double bonus(Player p,int type){int count=0;for(int id:profile(p).slots)if(id==type)count++;return switch(type){case ATTACK,DEFENSE->count*.03;case MP_REGEN->count*.2;case STAMINA_REGEN->count*.06;case ULT->count*.05;case SPEED->count*.02;default->0;};}
  public boolean grant(Player p,int id){if(id<0||id>=NAMES.length||!profile(p).owned.add(id))return false;save(p);p.sendMessage("§d[ACCESSORY] §f"+NAMES[id]+" §aを獲得！ §7/sa accessories で装備");return true;}
  public void reward(Player p){List<Integer> missing=new ArrayList<>();for(int i=0;i<NAMES.length;i++)if(!profile(p).owned.contains(i))missing.add(i);if(missing.isEmpty()){p.sendMessage("§e[ACCESSORY] 全アクセサリ収集済み！");return;}grant(p,missing.get(new Random().nextInt(missing.size())));}
  private ItemStack icon(Material mat,String name,String... lines){ItemStack item=new ItemStack(mat);ItemMeta meta=item.getItemMeta();meta.setDisplayName(name);meta.setLore(Arrays.asList(lines));item.setItemMeta(meta);return item;}
  public void open(Player p){Inventory inv=Bukkit.createInventory(null,45,TITLE);Profile x=profile(p);for(int i=0;i<4;i++){int id=x.slots[i];inv.setItem(10+i*2,id<0?icon(Material.GRAY_STAINED_GLASS_PANE,"§7SLOT "+(i+1)+" §8[空]","§7所持アクセサリをクリックで装備"):icon(ICONS[id],"§aSLOT "+(i+1)+" §f"+NAMES[id],"§7"+DESCS[id],"§cクリックで解除"));}for(int id=0;id<NAMES.length;id++){boolean owned=x.owned.contains(id);boolean equipped=false;for(int slot:x.slots)if(slot==id)equipped=true;inv.setItem(27+id,icon(owned?ICONS[id]:Material.GRAY_DYE,(owned?"§e":"§8")+NAMES[id],"§7"+DESCS[id],owned?(equipped?"§a装備中":"§aクリックで装備"):"§8未所持 / ボス討伐で入手"));}inv.setItem(40,icon(Material.BARRIER,"§c閉じる"));p.openInventory(inv);}
  @EventHandler public void click(InventoryClickEvent e){if(!TITLE.equals(e.getView().getTitle()))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p))return;int raw=e.getRawSlot();if(raw<0||raw>=45)return;Profile x=profile(p);if(raw==40){p.closeInventory();return;}if(raw>=27&&raw<33){int id=raw-27;if(!x.owned.contains(id))return;for(int slot:x.slots)if(slot==id)return;for(int i=0;i<4;i++)if(x.slots[i]<0){x.slots[i]=id;save(p);open(p);return;}p.sendMessage("§cアクセサリ装備枠は最大4つです。先に解除してください。");return;}for(int i=0;i<4;i++)if(raw==10+i*2&&x.slots[i]>=0){x.slots[i]=-1;save(p);open(p);return;}}
  @EventHandler public void drag(InventoryDragEvent e){if(TITLE.equals(e.getView().getTitle()))e.setCancelled(true);}
  @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent e){dungeon.remove(e.getPlayer().getUniqueId());profiles.remove(e.getPlayer().getUniqueId());}
}
