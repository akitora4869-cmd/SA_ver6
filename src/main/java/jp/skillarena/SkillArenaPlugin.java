package jp.skillarena;

import org.bukkit.*;
import jp.skillarena.boss.VargasBoss;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.util.Vector;
import org.bukkit.util.RayTraceResult;
import java.io.*;
import java.util.*;

/** SkillArena v0.4: distinct skill-family mechanics, safer combat resolution, and ultimate corrections. */
public final class SkillArenaPlugin extends JavaPlugin implements Listener {
  private static final String MENU="§8SkillArena | Build", PICK="§8SkillArena | Skills", ULTS="§8SkillArena | Ultimates";
  private static final String[] SCHOOLS={"炎","氷","雷","闇","光","武術","空間","禁術"};
  private static final String[][] BASE={{"火炎弾","爆炎陣"},{"氷槍","氷結領域"},{"雷撃","雷迅"},{"影弾","影縛り"},{"光弾","聖域"},{"斬撃","衝撃拳"},{"空間刃","転位"},{"呪弾","生命吸収"}};
  private static final String[] SUFFIX={"","・改","・連","・極"};
  private static final Material[] MAT={Material.BLAZE_POWDER,Material.PRISMARINE_SHARD,Material.GLOWSTONE_DUST,Material.ENDER_EYE,Material.GHAST_TEAR,Material.IRON_SWORD,Material.ENDER_PEARL,Material.WITHER_ROSE};
  private static final Particle[] FX={Particle.FLAME,Particle.SNOWFLAKE,Particle.ELECTRIC_SPARK,Particle.PORTAL,Particle.END_ROD,Particle.CRIT,Particle.REVERSE_PORTAL,Particle.SOUL_FIRE_FLAME};
  private static final String[] ULT={"アースクエイク","フォースマジック","ジャッジメント","居合切り","ブレイクアウト","クロノスタシス","オーバードライブ","デストラクションブレード"};
  private static final String[] ULT_PLUS={"グランドクロス","セブンスマジック","エスカトンジャッジメント","覇王一閃","オブセッション","クロノブレイク","アンリミテッドドライブ","ラグナロク"};
  private static final String[] SWORD={"ブレイク","ブラスト","テンペスト","ジェノサイド"};
  private static final String[] SWORD_PLUS={"デッドブレイク","アークブラスト","エンドテンペスト","ノーハート"};
  private NamespacedKey key; private File folder; private VargasBoss vargas; private AccessoryManager accessories;
  public void rewardBossAccessory(Player p){accessories.reward(p);}
  private final Map<UUID,Fighter> fighters=new HashMap<>();
  private final Map<UUID,Integer> picking=new HashMap<>();
  private final Random rng=new Random();
  private static class Fighter {
    double mp=100, gauge=0, stored=0; int[] load={0,4,8,12}; int ultimate=0; boolean upgraded=false;
    int[] mastery=new int[16], masteryProgress=new int[16], ultUses=new int[8]; long[] cd=new long[64];
    long dodge=0, iframe=0, locked=0, ultChargeEnd=0, ultEnd=0, noGain=0, breakEnd=0, buffEnd=0, timeEnd=0;
    long[] swordCd=new long[4]; int masteryHits=0;
    double stamina=14.0; boolean staminaExhausted=false;
    long lastSneak=0, mobilityDashCd=0, doubleJumpCd=0; boolean airborneJumpUsed=false;
    int combo=0, swordHits=0; long comboAt=0; boolean sword=false, swordPlus=false, counter=false;
  }
  @Override public void onEnable(){
    key=new NamespacedKey(this,"sa_item"); folder=new File(getDataFolder(),"players"); folder.mkdirs();
    Bukkit.getPluginManager().registerEvents(this,this); accessories=new AccessoryManager(this); vargas=new VargasBoss(this); for(Player p:Bukkit.getOnlinePlayers()){load(p);configureHealth(p,false);configureStamina(p);}
    Bukkit.getScheduler().runTaskTimer(this,()->{
      long now=System.currentTimeMillis();
      for(Player p:Bukkit.getOnlinePlayers()){
        Fighter f=get(p); f.mp=Math.min(100,f.mp+2);
        tickStamina(p,f);
        if(accessories.isActive(p,vargas))f.mp=Math.min(100,f.mp+accessories.bonus(p,AccessoryManager.MP_REGEN));
        if(f.sword && now>=f.ultEnd){f.sword=false;equip(p);}
        if(f.counter && now>=f.ultEnd){f.counter=false;f.stored=0;}
        if(now>f.comboAt+3000)f.combo=0;
        if(f.counter&&now<f.ultEnd)p.getWorld().spawnParticle(Particle.ENCHANTMENT_TABLE,p.getLocation().add(0,1,0),2,.3,.5,.3,0);
        String hpColor=p.getHealth()<=25?"§c":p.getHealth()<=50?"§e":"§a";
        StringBuilder bar=new StringBuilder(hpColor+"HP "+(int)Math.ceil(p.getHealth())+"/"+(int)p.getMaxHealth()+" §7| "+(f.staminaExhausted?"§c§lOVERHEAT ":"§eST ")+(int)Math.ceil(f.stamina/14.0*100.0)+"% §7| §bMP "+(int)f.mp+"/100 §7| §6ULT "+(int)f.gauge+"% §7| ");
        for(int i=0;i<4;i++){
          long remain=Math.max(0,f.cd[f.load[i]]-now);
          bar.append("§e").append(i+1).append(remain==0?"§a✓ ":"§c"+String.format(Locale.ROOT,"%.1f ",remain/1000.));
        }
        p.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(bar.toString()));
      }
    },5,5);
  }
  /** 100 actual HP, but only ten hearts visually; exact HP stays in the action bar. */
  private void configureHealth(Player p,boolean reset){
    AttributeInstance max=p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
    if(max==null)return;
    double oldMax=max.getValue();
    double oldHealth=p.getHealth();
    max.setBaseValue(100.0);
    p.setHealthScaled(true);
    p.setHealthScale(20.0);
    if(reset && !p.isDead())p.setHealth(100.0);
    else if(!p.isDead() && oldMax>0)p.setHealth(Math.max(1.0,Math.min(100.0,oldHealth/oldMax*100.0)));
  }
  /** The visible hunger bar reserves six food points: 6 = 0% ST, 20 = 100% ST. */
  private static final double STAMINA_MAX=14.0;
  private static final int STAMINA_FOOD_FLOOR=6;
  private int staminaFood(Fighter f){return Math.max(STAMINA_FOOD_FLOOR,Math.min(20,STAMINA_FOOD_FLOOR+(int)Math.ceil(f.stamina)));}
  private void configureStamina(Player p){
    Fighter f=get(p);
    p.setFoodLevel(staminaFood(f));
    p.setSaturation(0f);
    p.setExhaustion(0f);
    applyMobility(p,f);
    updateDoubleJump(p,f);
  }
  private void applyMobility(Player p,Fighter f){
    // Faster ground movement, with a separate sprint boost. Bukkit sprinting also
    // multiplies walk speed, so the sprint value intentionally produces a fast dash.
    boolean active=!f.staminaExhausted && f.stamina>0.01;
    double baseSpeed=p.isSprinting()?0.44:0.37;
    double accessoryFactor=accessories!=null&&accessories.isActive(p,vargas)?1+accessories.bonus(p,AccessoryManager.SPEED):1;
    p.setWalkSpeed(f.staminaExhausted?0f:(active?(float)Math.min(1.0,baseSpeed*accessoryFactor):0.2f));
    if(active){
      p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP,18,0,true,false,false));
    }else{
      PotionEffect current=p.getPotionEffect(PotionEffectType.JUMP);
      if(current!=null&&current.getAmplifier()==0&&current.getDuration()<=18)p.removePotionEffect(PotionEffectType.JUMP);
    }
  }
  private void tickStamina(Player p,Fighter f){
    if(p.isDead()||p.getGameMode()==GameMode.SPECTATOR)return;
    double before=f.stamina;
    // Called every five ticks (0.25s): sprinting spends stamina, rest replenishes it.
    if(p.isSprinting()&&f.stamina>0)f.stamina=Math.max(0,f.stamina-0.154);
    else if(f.staminaExhausted)f.stamina=Math.min(STAMINA_MAX,f.stamina+0.35); // 40 updates (10 seconds) to full recovery
    else if(f.stamina>0)f.stamina=Math.min(STAMINA_MAX,f.stamina+(p.isSneaking()?0.196:0.112)*(accessories.isActive(p,vargas)?1.0+accessories.bonus(p,AccessoryManager.STAMINA_REGEN):1.0));
    // Exhaustion is a latched state: mobility returns ONLY when fully recovered.
    if(f.stamina<=0.0001 && !f.staminaExhausted){
      f.stamina=0;f.staminaExhausted=true;
      // Exhaustion disables every active defensive stance and temporary immunity.
      f.iframe=0;f.counter=false;f.stored=0;f.timeEnd=0;f.buffEnd=0;
      p.removePotionEffect(PotionEffectType.SPEED);
      p.setAllowFlight(false);
      p.setSprinting(false);p.setVelocity(new Vector(0,0,0));
      p.sendTitle("§c§lOVERHEAT", "§7SYSTEM LOCKED §8/ §c10 SEC", 5, 35, 10);
      p.playSound(p.getLocation(),org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS,0.85f,0.55f);
      p.sendMessage("§c§l[OVERHEAT] §7全システム停止。約10秒間、移動・スキル・回避・防御が不可能です。");
    }
    if(f.staminaExhausted){
      p.setSprinting(false);
      if(f.stamina>=STAMINA_MAX-0.0001){
        f.stamina=STAMINA_MAX;f.staminaExhausted=false;
        p.sendTitle("§a§lSYSTEM READY", "§7Mobility / Skills / Defense ONLINE", 3, 25, 8);
        p.playSound(p.getLocation(),org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING,0.85f,1.7f);
        p.sendMessage("§a§l[SYSTEM READY] §7スタミナ全回復。全システム復帰。");
      }else{
        // Stop momentum, including external knockback, while exhausted.
        p.setVelocity(new Vector(0,0,0));
      }
    }
    if(Math.ceil(before)!=Math.ceil(f.stamina)||p.getFoodLevel()!=staminaFood(f)){
      p.setFoodLevel(staminaFood(f));
    }
    p.setSaturation(0f);p.setExhaustion(0f);
    applyMobility(p,f);
    updateDoubleJump(p,f);
  }
  @EventHandler(ignoreCancelled=true) public void exhaustedMove(PlayerMoveEvent e){
    Fighter f=get(e.getPlayer());
    if((!f.staminaExhausted && System.currentTimeMillis()>=f.ultChargeEnd) || e.getPlayer().getGameMode()==GameMode.SPECTATOR)return;
    if(e.getTo()==null)return;
    // Preserve camera rotation; reject any positional movement, including jumps.
    if(e.getFrom().distanceSquared(e.getTo())>0){
      Location fixed=e.getFrom().clone();
      fixed.setYaw(e.getTo().getYaw());fixed.setPitch(e.getTo().getPitch());
      e.setTo(fixed);
    }
  }
  /** Built-in movement is independent of equipped skills, but consumes the shared stamina bar. */
  private boolean mobilityAllowed(Player p,Fighter f){
    return !p.isDead() && (p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE)
      && !f.staminaExhausted && System.currentTimeMillis()>=f.ultChargeEnd && System.currentTimeMillis()>=f.locked;
  }
  private boolean spendMobility(Player p,Fighter f,double amount){
    if(!mobilityAllowed(p,f)||f.stamina<amount)return false;
    f.stamina=Math.max(0,f.stamina-amount);
    p.setFoodLevel(staminaFood(f));
    // Exhaustion is latched by the normal stamina tick, including when a move spends the last point.
    if(f.stamina<=0)tickStamina(p,f);
    return true;
  }
  private void updateDoubleJump(Player p,Fighter f){
    if(p.getGameMode()!=GameMode.SURVIVAL&&p.getGameMode()!=GameMode.ADVENTURE)return;
    if(p.isOnGround())f.airborneJumpUsed=false;
    boolean allowed=mobilityAllowed(p,f)&&!p.isOnGround()&&!f.airborneJumpUsed&&f.stamina>=2.0
      && System.currentTimeMillis()>=f.doubleJumpCd;
    if(p.getAllowFlight()!=allowed)p.setAllowFlight(allowed);
    if(!allowed&&p.isFlying())p.setFlying(false);
  }
  /** Press jump once more while airborne; Bukkit delivers a flight-toggle packet. */
  @EventHandler(ignoreCancelled=true) public void doubleJump(PlayerToggleFlightEvent e){
    Player p=e.getPlayer();if(p.getGameMode()!=GameMode.SURVIVAL&&p.getGameMode()!=GameMode.ADVENTURE)return;
    e.setCancelled(true);p.setFlying(false);
    Fighter f=get(p);long now=System.currentTimeMillis();
    if(p.isOnGround()||f.airborneJumpUsed||now<f.doubleJumpCd||!spendMobility(p,f,2.0))return;
    f.airborneJumpUsed=true;f.doubleJumpCd=now+500;
    Vector v=p.getVelocity();Vector facing=p.getLocation().getDirection().setY(0);
    if(facing.lengthSquared()>0.001)facing.normalize().multiply(0.35);
    else facing=new Vector(0,0,0);
    p.setVelocity(new Vector(v.getX()*0.4+facing.getX(),0.77,v.getZ()*0.4+facing.getZ()));
    p.getWorld().spawnParticle(Particle.CLOUD,p.getLocation().add(0,.2,0),20,.4,.12,.4,.035);
    p.playSound(p.getLocation(),Sound.ENTITY_BAT_TAKEOFF,.8f,1.5f);
    p.setAllowFlight(false);
  }
  /** Double-tap sneak to burst forward; single sneak + left click still uses Break Step. */
  @EventHandler(ignoreCancelled=true) public void mobilityDash(PlayerToggleSneakEvent e){
    if(!e.isSneaking())return;
    Player p=e.getPlayer();Fighter f=get(p);long now=System.currentTimeMillis();
    boolean doubleTap=now-f.lastSneak>=90 && now-f.lastSneak<=350;
    f.lastSneak=now;
    if(!doubleTap||now<f.mobilityDashCd||!spendMobility(p,f,1.5))return;
    Vector d=p.getLocation().getDirection().setY(0);
    if(d.lengthSquared()<0.001)return;
    f.mobilityDashCd=now+1800;
    Vector velocity=d.normalize().multiply(1.2);
    velocity.setY(Math.max(0.08,p.getVelocity().getY()));
    p.setVelocity(velocity);
    p.getWorld().spawnParticle(Particle.CLOUD,p.getLocation().add(0,.8,0),18,.3,.3,.3,.025);
    p.playSound(p.getLocation(),Sound.ENTITY_ENDERMAN_TELEPORT,.5f,1.65f);
  }
  @EventHandler(ignoreCancelled=true) public void hunger(FoodLevelChangeEvent e){
    if(e.getEntity() instanceof Player)e.setCancelled(true);
  }
  private Fighter get(Player p){return fighters.computeIfAbsent(p.getUniqueId(),id->new Fighter());}
  private File file(Player p){return new File(folder,p.getUniqueId()+".yml");}
  private void load(Player p){Fighter f=get(p);File x=file(p);if(x.isFile()){
    YamlConfiguration y=YamlConfiguration.loadConfiguration(x);
    for(int i=0;i<4;i++){int v=y.getInt("load."+i,f.load[i]);if(v>=0&&v<64)f.load[i]=v;}
    f.ultimate=Math.max(0,Math.min(7,y.getInt("ultimate",0)));f.upgraded=y.getBoolean("upgraded",false);
    for(int i=0;i<16;i++){f.mastery[i]=y.getInt("mastery."+i,0);f.masteryProgress[i]=y.getInt("masteryProgress."+i,0);}
    for(int i=0;i<8;i++)f.ultUses[i]=y.getInt("ultUses."+i,0);
  }equip(p);accessories.load(p);}
  private void save(Player p){accessories.save(p);Fighter f=get(p);YamlConfiguration y=new YamlConfiguration();
    for(int i=0;i<4;i++)y.set("load."+i,f.load[i]);y.set("ultimate",f.ultimate);y.set("upgraded",f.upgraded);
    for(int i=0;i<16;i++){y.set("mastery."+i,f.mastery[i]);y.set("masteryProgress."+i,f.masteryProgress[i]);}for(int i=0;i<8;i++)y.set("ultUses."+i,f.ultUses[i]);
    try{y.save(file(p));}catch(IOException ex){getLogger().warning("save: "+ex.getMessage());}}
  @Override public void onDisable(){if(vargas!=null)vargas.end(false);for(Player p:Bukkit.getOnlinePlayers()){save(p);p.setWalkSpeed(0.2f);p.removePotionEffect(PotionEffectType.JUMP);p.setFoodLevel(20);p.setSaturation(5f);}}
  @EventHandler public void join(PlayerJoinEvent e){load(e.getPlayer());configureHealth(e.getPlayer(),true);configureStamina(e.getPlayer());}
  @EventHandler public void quit(PlayerQuitEvent e){if(e.getPlayer().getGameMode()==GameMode.SURVIVAL||e.getPlayer().getGameMode()==GameMode.ADVENTURE)e.getPlayer().setAllowFlight(false);save(e.getPlayer());fighters.remove(e.getPlayer().getUniqueId());picking.remove(e.getPlayer().getUniqueId());}
  private static int family(int id){return id/4;} private static int school(int id){return id/8;}
  private static String skillName(int id){return BASE[school(id)][(id%8)/4]+SUFFIX[id%4];}
  private static int cost(int id){return 3+(id%4);}
  private static int mp(int id){return 12+(school(id)%4)*3+(id%4)*4;}
  private static long cooldown(int id){return 2500+(id%4)*750+(school(id)%3)*500;}
  private boolean unlocked(Fighter f,int id){int tier=id%4;return tier==0||f.mastery[family(id)]>=new int[]{0,3,6,9}[tier];}
  private ItemStack item(Material m,String name,String token,String... lore){ItemStack s=new ItemStack(m);ItemMeta meta=s.getItemMeta();meta.setDisplayName("§e"+name);meta.setLore(Arrays.asList(lore));meta.getPersistentDataContainer().set(key,PersistentDataType.STRING,token);s.setItemMeta(meta);return s;}
  private String token(ItemStack s){return s!=null&&s.hasItemMeta()?s.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.STRING):null;}
  private void equip(Player p){Fighter f=get(p);for(int i=0;i<4;i++){
    if(f.sword)p.getInventory().setItem(i,item(Material.NETHERITE_SWORD,(f.swordPlus?SWORD_PLUS:SWORD)[i],"SWORD:"+i,"§7右クリックで発動"));
    else {int id=f.load[i];p.getInventory().setItem(i,item(MAT[school(id)],skillName(id),"SKILL:"+i,"§7MP "+mp(id)+" / CT "+cooldown(id)/1000.0+"秒","§7右クリックで発動"));}}
    p.getInventory().setItem(4,item(Material.NETHER_STAR,(f.upgraded?ULT_PLUS:ULT)[f.ultimate],"ULT","§7ウルトゲージ100%で使用"));
    for(int i=5;i<8;i++)p.getInventory().setItem(i,null);
    p.getInventory().setItem(8,item(Material.BOOK,"スキル構築","MENU","§7右クリックで開く"));}
  private void menu(Player p){Inventory inv=Bukkit.createInventory(null,54,MENU);Fighter f=get(p);int sum=0;
    for(int i=0;i<4;i++){int id=f.load[i];sum+=cost(id);inv.setItem(10+i*2,item(MAT[school(id)],"枠"+(i+1)+": "+skillName(id),"EDIT:"+i,"§7構築コスト "+cost(id),"§7クリックして変更"));}
    inv.setItem(29,item(Material.PAPER,"構築コスト "+sum+"/20","INFO","§7合計20以下に制限"));
    inv.setItem(31,item(Material.NETHER_STAR,(f.upgraded?ULT_PLUS:ULT)[f.ultimate],"ULTMENU","§7クリックしてウルトを選択","§7上位版は基本版を実戦20回使用で解放"));
    inv.setItem(47,item(Material.AMETHYST_SHARD,"アクセサリ装備（4枠）","ACCESSORIES","§7ボス・ダンジョンでのみ効果が有効"));
    inv.setItem(49,item(Material.BARRIER,"閉じる","CLOSE"));p.openInventory(inv);}
  private void picker(Player p,int slot){picking.put(p.getUniqueId(),slot);Inventory inv=Bukkit.createInventory(null,54,PICK);Fighter f=get(p);
    for(int id=0;id<45;id++){int pos=(id/9)*9+(id%9);
      boolean ok=unlocked(f,id);inv.setItem(pos,item(ok?MAT[school(id)]:Material.GRAY_DYE,skillName(id)+(ok?"":" §c[未解放]"),"PICK:"+id,"§7"+SCHOOLS[school(id)]+" / コスト "+cost(id),"§7MP "+mp(id)+" CT "+cooldown(id)/1000.0+"秒","§7熟練度 "+f.mastery[family(id)]+" / 10"));}
    // 64 skills require pagination: 0..44 on page 1; remainder on page 2.
    inv.setItem(49,item(Material.ARROW,"次のページ","PAGE:1"));p.openInventory(inv);}
  private void pickerPage2(Player p){Inventory inv=Bukkit.createInventory(null,27,PICK);Fighter f=get(p);
    for(int id=45;id<64;id++){boolean ok=unlocked(f,id);inv.setItem(id-45,item(ok?MAT[school(id)]:Material.GRAY_DYE,skillName(id)+(ok?"":" §c[未解放]"),"PICK:"+id,"§7コスト "+cost(id),"§7熟練度 "+f.mastery[family(id)]+" / 10"));}
    inv.setItem(22,item(Material.ARROW,"戻る","PAGE:0"));p.openInventory(inv);}
  private void ultimateMenu(Player p){Inventory inv=Bukkit.createInventory(null,27,ULTS);Fighter f=get(p);
    for(int i=0;i<8;i++){inv.setItem(i,item(Material.NETHER_STAR,ULT[i],"ULTSEL:"+i,"§7基本版使用回数 "+f.ultUses[i]+"/20"));
      inv.setItem(i+9,item(f.ultUses[i]>=20?Material.NETHER_STAR:Material.GRAY_DYE,ULT_PLUS[i],"ULTPLUS:"+i,"§7基本版20回で解放"));}
    inv.setItem(22,item(Material.ARROW,"戻る","BACK"));p.openInventory(inv);}
  @EventHandler public void click(InventoryClickEvent e){String title=e.getView().getTitle();if(!title.equals(MENU)&&!title.equals(PICK)&&!title.equals(ULTS))return;
    e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p))return;if(e.getRawSlot()<0||e.getRawSlot()>=e.getView().getTopInventory().getSize())return;
    String t=token(e.getCurrentItem());if(t==null)return;Fighter f=get(p);
    if(t.equals("ACCESSORIES")){accessories.open(p);return;}if(t.equals("CLOSE")){p.closeInventory();return;}if(t.equals("BACK")){menu(p);return;}
    if(t.equals("ULTMENU")){ultimateMenu(p);return;}if(t.startsWith("EDIT:")){picker(p,Integer.parseInt(t.substring(5)));return;}
    if(t.equals("PAGE:1")){pickerPage2(p);return;}if(t.equals("PAGE:0")){picker(p,picking.getOrDefault(p.getUniqueId(),0));return;}
    if(t.startsWith("PICK:")){int id=Integer.parseInt(t.substring(5));int slot=picking.getOrDefault(p.getUniqueId(),-1);if(slot<0||slot>3||!unlocked(f,id))return;
      int total=cost(id);for(int i=0;i<4;i++)if(i!=slot)total+=cost(f.load[i]);if(total>20){p.sendMessage("§c構築コスト20を超えます ("+total+")");return;}
      f.load[slot]=id;save(p);equip(p);menu(p);return;}
    if(t.startsWith("ULTSEL:")||t.startsWith("ULTPLUS:")){boolean plus=t.startsWith("ULTPLUS:");int id=Integer.parseInt(t.substring(plus?8:7));if(plus&&f.ultUses[id]<20){p.sendMessage("§c基本版を実戦で20回使用すると解放されます。");return;}
      f.ultimate=id;f.upgraded=plus;save(p);equip(p);menu(p);}
  }
  @EventHandler public void drag(InventoryDragEvent e){String t=e.getView().getTitle();if(t.equals(MENU)||t.equals(PICK)||t.equals(ULTS))e.setCancelled(true);}
  @EventHandler public void inventoryMove(InventoryClickEvent e){
    if(!(e.getWhoClicked() instanceof Player))return;
    if(e.getView().getTitle().equals(MENU)||e.getView().getTitle().equals(PICK)||e.getView().getTitle().equals(ULTS))return;
    if(token(e.getCurrentItem())!=null||token(e.getCursor())!=null)e.setCancelled(true);
  }
  @EventHandler public void drop(PlayerDropItemEvent e){if(token(e.getItemDrop().getItemStack())!=null)e.setCancelled(true);}
  @EventHandler public void interact(PlayerInteractEvent e){if(e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND)return;Player p=e.getPlayer();Action a=e.getAction();
    if(p.isSneaking()&&(a==Action.LEFT_CLICK_AIR||a==Action.LEFT_CLICK_BLOCK)){dodge(p);e.setCancelled(true);return;}
    if(a!=Action.RIGHT_CLICK_AIR&&a!=Action.RIGHT_CLICK_BLOCK)return;String t=token(p.getInventory().getItemInMainHand());if(t==null)return;e.setCancelled(true);
    if(t.equals("MENU"))menu(p);else if(t.equals("ULT"))ultimate(p);else if(t.startsWith("SKILL:"))cast(p,Integer.parseInt(t.substring(6)));else if(t.startsWith("SWORD:"))sword(p,Integer.parseInt(t.substring(6)));
  }
  @EventHandler public void animation(PlayerAnimationEvent e){if(e.getAnimationType()==PlayerAnimationType.ARM_SWING&&e.getPlayer().isSneaking())dodge(e.getPlayer());}
  private void dodge(Player p){Fighter f=get(p);if(f.staminaExhausted)return;long now=System.currentTimeMillis();if(now<f.dodge||now<f.locked||p.isDead())return;
    Vector d=p.getLocation().getDirection().setY(0);if(d.lengthSquared()<.01)return;f.dodge=now+3000;f.iframe=now+150;if(vargas!=null)vargas.registerDodge(p,now+150);
    p.setVelocity(d.normalize().multiply(1.35).setY(.08));p.getWorld().spawnParticle(Particle.CLOUD,p.getLocation().add(0,1,0),20,.4,.5,.4,.03);}
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void damage(EntityDamageEvent e){
    if(e.getEntity() instanceof Player && e.getCause()==EntityDamageEvent.DamageCause.FALL){e.setCancelled(true);return;}
    if(!(e.getEntity() instanceof Player p))return;Fighter f=get(p);long now=System.currentTimeMillis();
    if(accessories.isActive(p,vargas))e.setDamage(e.getDamage()*Math.max(0.7,1.0-accessories.bonus(p,AccessoryManager.DEFENSE)));
    if(!f.staminaExhausted && now<f.iframe){e.setCancelled(true);return;}if(!f.staminaExhausted && f.counter&&now<f.ultEnd){f.stored=Math.min(66.667,f.stored+e.getDamage());e.setDamage(e.getDamage()*.7);}
  }
  private boolean enemy(Player p,Entity e){return e instanceof LivingEntity&&e!=p&&e.isValid()&&!(e instanceof ArmorStand)&&(!(e instanceof Player q)||q.getGameMode()==GameMode.SURVIVAL||q.getGameMode()==GameMode.ADVENTURE);}
  private List<LivingEntity> nearby(Player p,Location at,double r){List<LivingEntity> result=new ArrayList<>();for(Entity e:at.getWorld().getNearbyEntities(at,r,r,r))if(enemy(p,e)&&e.getLocation().distanceSquared(at)<=r*r)result.add((LivingEntity)e);return result;}
  private boolean hit(Player p,LivingEntity target,double damage){if(target instanceof Player q&&!get(q).staminaExhausted&&System.currentTimeMillis()<get(q).iframe)return false;
    Fighter f=get(p);if(f.counter){damage+=Math.min(40,f.stored*.6);f.counter=false;f.stored=0;f.ultEnd=0;}
    target.damage(damage*1.5*(accessories.isActive(p,vargas)?1.0+accessories.bonus(p,AccessoryManager.ATTACK):1.0),p);if(System.currentTimeMillis()>=f.noGain)f.gauge=Math.min(100,f.gauge+8*(accessories.isActive(p,vargas)?1+accessories.bonus(p,AccessoryManager.ULT):1));return true;}
  private LivingEntity ray(Player p,double range,double radius){Location eye=p.getEyeLocation();Vector d=eye.getDirection();RayTraceResult b=p.getWorld().rayTraceBlocks(eye,d,range,FluidCollisionMode.NEVER,true);
    double max=b==null?range:Math.max(0,b.getHitPosition().distance(eye.toVector())-.02);
    RayTraceResult h=p.getWorld().rayTraceEntities(eye,d,max,radius,e->enemy(p,e));return h!=null&&h.getHitEntity() instanceof LivingEntity l?l:null;}
  private void mastery(Player p,int id){Fighter f=get(p);int family=family(id);f.masteryProgress[family]++;if(f.masteryProgress[family]%5!=0)return;f.mastery[family]=Math.min(10,f.mastery[family]+1);if(f.mastery[family]==3||f.mastery[family]==6||f.mastery[family]==9)p.sendMessage("§a"+BASE[school(id)][(id%8)/4]+"の派生技が解放されました。");save(p);}
  /** The "連" evolution is a real three-hit sequence, not just a damage/range upgrade.
   * A single cast grants only one mastery/combo credit; each hit has its own impact FX.
   * The damage is split to prevent tripling the intended total damage. */
  private void chainHits(Player caster, LivingEntity target, double totalDamage, int school){
    if(target==null||!target.isValid()||target.isDead())return;
    final java.util.UUID id=target.getUniqueId();
    final World world=target.getWorld();
    for(int strike=0;strike<3;strike++){
      final int index=strike;
      Bukkit.getScheduler().runTaskLater(this,()->{
        if(!caster.isOnline()||caster.isDead()||!target.isValid()||target.isDead()
            ||!target.getUniqueId().equals(id)||!target.getWorld().equals(world))return;
        // Vanilla hurt-resistance otherwise silently absorbs subsequent strikes.
        if(index>0)target.setNoDamageTicks(0);
        if(hit(caster,target,totalDamage/3.0)){
          effect(school,target);
          Location impact=target.getLocation().add(0,Math.min(1.2,target.getHeight()*.5),0);
          world.spawnParticle(FX[school],impact,16,.25,.3,.25,.05);
          world.spawnParticle(Particle.CRIT,impact,10,.22,.25,.22,.07);
          world.playSound(impact,Sound.ENTITY_PLAYER_ATTACK_SWEEP,.5f,1.25f+index*.14f);
        }
      },strike*4L);
    }
  }
  private void evolvedImpact(Player caster,LivingEntity target,double damage,int school,int tier){
    if(tier==2)chainHits(caster,target,damage,school);
    else if(hit(caster,target,damage))effect(school,target);
  }
  /** 16 families have distinct execution paths; tiers improve reach, duration and effects. */
  private void cast(Player p,int slot){
    Fighter f=get(p);if(f.staminaExhausted)return;long now=System.currentTimeMillis();if(now<f.locked||p.isDead()||f.sword)return;
    int id=f.load[slot];if(now<f.cd[id])return;
    if(f.mp<mp(id)){p.sendMessage("§cMP不足");return;}
    f.mp-=mp(id);f.cd[id]=now+(long)(cooldown(id)*(now<f.buffEnd?.7:1));
    int school=school(id),family=(id%8)/4,tier=id%4;double damage=8+tier*2;
    double range=13+tier*2; boolean landed=false;
    Location at=p.getLocation().add(p.getLocation().getDirection().multiply(4));
    if(school==6&&family==1){ // 転位: teleport only to a verified unobstructed destination
      Location eye=p.getEyeLocation();Vector dir=eye.getDirection();
      RayTraceResult block=p.getWorld().rayTraceBlocks(eye,dir,5+tier,FluidCollisionMode.NEVER,true);
      double distance=block==null?5+tier:Math.max(0,block.getHitPosition().distance(eye.toVector())-1.2);
      Location dest=p.getLocation().add(dir.multiply(distance));
      if(dest.getBlock().isPassable()&&dest.clone().add(0,1,0).getBlock().isPassable()){Location before=p.getLocation();p.teleport(dest);dramaticSkillFx(p,school,tier,before.add(0,1,0),false);}
    }else if(family==0){
      // A projectile moves through the world over time; only impact applies damage.
      if(school==5){
        LivingEntity t=ray(p,range,1.0+tier*.08);
        if(t!=null){evolvedImpact(p,t,damage,school,tier);landed=true;
          t.setVelocity(t.getVelocity().add(p.getLocation().getDirection().multiply(.35+tier*.1)));
        }
        swordArcFx(p,p.getEyeLocation(),p.getEyeLocation().getDirection(),tier,false);
      }else{
        launchSkillProjectile(p,school,tier,range,damage,id);
        // Mastery/combo credit is granted at impact, not at cast time.
      }
    }else{ // Family 1: area control, mobility, or melee burst depending on school
      Location center=(school==1||school==4||school==3)?p.getLocation():at;
      double radius=2.5+tier*.55;
      dramaticSkillFx(p,school,tier,center.clone().add(0,1,0),true);
      if(school==5)shockwaveFx(center,radius);
      for(LivingEntity t:nearby(p,center,radius)){
        if(school==5&&!p.hasLineOfSight(t))continue;
        if(tier==2 || hit(p,t,damage*.8)){if(tier==2)chainHits(p,t,damage*.8,school);else effect(school,t);landed=true;
          if(school==1)t.addPotionEffect(new PotionEffect(PotionEffectType.SLOW,50+tier*10,1));
          if(school==2)t.setVelocity(t.getVelocity().add(new Vector(0,.2+tier*.1,0)));
          if(school==5)shockwaveKnockback(t,center,1.0+tier*.32);
          if(school==3)t.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,20+tier*5,0));
          if(school==4)p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,35+tier*10,0));
          if(school==7)p.setHealth(Math.min(p.getMaxHealth(),p.getHealth()+1+tier));
        }
      }
    }
    if(landed){
      f.combo=now<f.comboAt+3000?f.combo+1:1;f.comboAt=now;
      if(f.combo>=4){f.combo=0;f.dodge=0;p.sendMessage("§bBREAKSTEP READY");}
      mastery(p,id);
    }
  }

  /** Animated projectile with a bright core and an attribute-specific sparkling trail. */
  private void launchSkillProjectile(Player caster,int school,int tier,double range,double damage,int id){
    Location origin=caster.getEyeLocation().add(caster.getEyeLocation().getDirection().multiply(.65));
    Vector velocity=caster.getEyeLocation().getDirection().normalize().multiply(1.18+tier*.07);
    World world=caster.getWorld();
    world.playSound(origin,school==0?Sound.ENTITY_BLAZE_SHOOT:Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,1.0f,1.1f);
    new org.bukkit.scheduler.BukkitRunnable(){
      Location pos=origin.clone();double traveled=0;int tick=0;
      @Override public void run(){
        if(!caster.isOnline()||caster.isDead()||!caster.getWorld().equals(world)||traveled>=range){cancel();return;}
        if(vargas!=null)vargas.warnProjectile(caster,pos,velocity);
        Location next=pos.clone().add(velocity);Vector direction=velocity.clone().normalize();
        RayTraceResult wall=world.rayTraceBlocks(pos,direction,velocity.length(),FluidCollisionMode.NEVER,true);
        RayTraceResult entity=world.rayTraceEntities(pos,direction,velocity.length(),.48+tier*.06,e->enemy(caster,e));
        double wallDistance=wall==null?Double.POSITIVE_INFINITY:wall.getHitPosition().distance(pos.toVector());
        double entityDistance=entity==null?Double.POSITIVE_INFINITY:entity.getHitPosition().distance(pos.toVector());
        boolean impact=wall!=null||entity!=null;
        if(impact){double dist=Math.min(wallDistance,entityDistance);next=pos.clone().add(direction.multiply(Math.max(0,dist)));}
        for(double fraction=.25;fraction<=1;fraction+=.25){Location point=pos.clone().add(next.toVector().subtract(pos.toVector()).multiply(fraction));projectileFx(world,point,school,tier,tick);}
        traveled+=pos.distance(next);pos=next;tick++;
        if(impact){
          if(entityDistance<wallDistance&&entity.getHitEntity() instanceof LivingEntity target){
            evolvedImpact(caster,target,damage,school,tier);if(school==7)caster.setHealth(Math.min(caster.getMaxHealth(),caster.getHealth()+2+tier));
            Fighter f=get(caster);long now=System.currentTimeMillis();f.combo=now<f.comboAt+3000?f.combo+1:1;f.comboAt=now;
            if(f.combo>=4){f.combo=0;f.dodge=0;caster.sendMessage("§bBREAKSTEP READY");}
            mastery(caster,id);
          }
          impactFx(world,pos,school,tier);cancel();return;
        }
        if(traveled>=range){impactFx(world,pos,school,tier);cancel();}
      }
    }.runTaskTimer(this,0L,1L);
  }
  private void projectileFx(World w,Location loc,int school,int tier,int tick){
    Particle core=FX[school];double r=.19+tier*.045;
    w.spawnParticle(core,loc,9+tier*3,r,r,r,.015);
    // Fire has a ghast-fireball-like incandescent body with crackling embers.
    if(school==0){w.spawnParticle(Particle.FLAME,loc,12+tier*4,r,r,r,.035);w.spawnParticle(Particle.LAVA,loc,2+tier,.23,.23,.23,.015);w.spawnParticle(Particle.SMALL_FLAME,loc,7,.3,.3,.3,.025);}
    else if(school==1){w.spawnParticle(Particle.SNOWFLAKE,loc,10,.26,.26,.26,.025);w.spawnParticle(Particle.CLOUD,loc,3,.12,.12,.12,.01);}
    else if(school==2){w.spawnParticle(Particle.ELECTRIC_SPARK,loc,15,.35,.35,.35,.13);w.spawnParticle(Particle.END_ROD,loc,3,.2,.2,.2,.01);}
    else if(school==3){w.spawnParticle(Particle.SMOKE_NORMAL,loc,8,.26,.26,.26,.025);w.spawnParticle(Particle.PORTAL,loc,8,.3,.3,.3,.08);}
    else if(school==4){w.spawnParticle(Particle.END_ROD,loc,12,.27,.27,.27,.025);w.spawnParticle(Particle.FIREWORKS_SPARK,loc,7,.25,.25,.25,.06);}
    else if(school==6){w.spawnParticle(Particle.REVERSE_PORTAL,loc,12,.3,.3,.3,.06);w.spawnParticle(Particle.CRIT_MAGIC,loc,5,.18,.18,.18,.05);}
    else {w.spawnParticle(Particle.SOUL_FIRE_FLAME,loc,12,.3,.3,.3,.025);w.spawnParticle(Particle.SOUL,loc,5,.24,.24,.24,.04);}
    if(tick%3==0)w.spawnParticle(Particle.FIREWORKS_SPARK,loc,3,.24,.24,.24,.05);
  }
  private void impactFx(World w,Location loc,int school,int tier){
    w.spawnParticle(FX[school],loc,35+tier*14,.6,.6,.6,.12);
    w.spawnParticle(Particle.FIREWORKS_SPARK,loc,25+tier*8,.5,.5,.5,.13);
    if(school==0)w.spawnParticle(Particle.FLAME,loc,45,.65,.65,.65,.12);
    w.playSound(loc,school==2?Sound.ENTITY_LIGHTNING_BOLT_IMPACT:Sound.ENTITY_GENERIC_EXPLODE,.8f,1.25f);
  }
  /** Curved sword-swipe ribbons rather than a spherical particle cloud. */
  private void swordArcFx(Player p,Location center,Vector facing,int tier,boolean heavy){
    Vector forward=facing.clone().setY(0);if(forward.lengthSquared()<.001)forward=new Vector(0,0,1);forward.normalize();
    Vector side=new Vector(-forward.getZ(),0,forward.getX());World w=p.getWorld();
    double reach=heavy?3.5:2.2;
    for(int ribbon=0;ribbon<(heavy?3:2);ribbon++)for(int i=0;i<=30;i++){
      double angle=-1.22+i*(2.44/30);double distance=reach+ribbon*.26;
      Location point=center.clone().add(forward.clone().multiply(Math.cos(angle)*distance)).add(side.clone().multiply(Math.sin(angle)*distance));
      point.add(0,Math.sin(i*Math.PI/30)*.8+ribbon*.12-.25,0);
      w.spawnParticle(ribbon==0?Particle.SWEEP_ATTACK:Particle.CRIT_MAGIC,point,1,0,0,0,0);
      w.spawnParticle(Particle.END_ROD,point,1,.045,.045,.045,0);
    }
    w.playSound(center,Sound.ENTITY_PLAYER_ATTACK_SWEEP,1.15f,heavy?.65f:1.1f);
  }
  /** Purely visual particles: damage, range and cooldown are unchanged. */
  private void dramaticSkillFx(Player p,int school,int tier,Location destination,boolean area){
    World w=p.getWorld();Location origin=p.getEyeLocation();
    Particle primary=FX[school];int density=12+tier*6;
    w.spawnParticle(primary,origin,20+tier*8,.55,.65,.55,.07);
    w.spawnParticle(Particle.CRIT,origin,12+tier*5,.5,.45,.5,.12);
    Vector travel=destination.toVector().subtract(origin.toVector());
    double length=Math.min(28,travel.length());
    if(length>.05){Vector direction=travel.normalize();
      for(double d=0;d<length;d+=.65){Location spot=origin.clone().add(direction.clone().multiply(d));
        w.spawnParticle(primary,spot,2+tier,.14,.14,.14,.01);
        if(((int)(d*10))%13<6)w.spawnParticle(Particle.END_ROD,spot,1,.08,.08,.08,0);
      }
    }
    w.spawnParticle(primary,destination,density*3,.7+tier*.2,.65,.7+tier*.2,.08);
    w.spawnParticle(Particle.FIREWORKS_SPARK,destination,18+tier*10,.55,.55,.55,.13);
    if(area){double radius=2.5+tier*.55;
      for(int ring=0;ring<2;ring++)for(int i=0;i<36;i++){
        double angle=i*Math.PI/18;Location dot=destination.clone().add(Math.cos(angle)*radius,ring*.65,Math.sin(angle)*radius);
        w.spawnParticle(primary,dot,2,0,0,0,0);
      }
    }
    w.playSound(destination,school==2?Sound.ENTITY_LIGHTNING_BOLT_IMPACT:Sound.ENTITY_GENERIC_EXPLODE,.65f,1.2f+tier*.09f);
  }
  /** Wind-charge-like impact: radial horizontal push plus a controlled upward lift. */
  private void shockwaveKnockback(LivingEntity target,Location center,double power){
    Vector away=target.getLocation().toVector().subtract(center.toVector()).setY(0);
    if(away.lengthSquared()<0.0001)away=new Vector(0,0,1);
    away.normalize();
    double horizontal=Math.min(1.65,0.75+power*.35);
    double vertical=Math.min(0.95,0.35+power*.19);
    target.setVelocity(new Vector(away.getX()*horizontal,vertical,away.getZ()*horizontal));
  }
  private void shockwaveFx(Location center,double radius){
    World w=center.getWorld();if(w==null)return;
    Location origin=center.clone().add(0,.35,0);
    for(int ring=1;ring<=3;ring++){
      double r=radius*ring/3.0;
      for(int i=0;i<36;i++){
        double a=i*Math.PI/18.0;
        w.spawnParticle(Particle.CLOUD,origin.clone().add(Math.cos(a)*r,.08*ring,Math.sin(a)*r),2,0,.06,0,.025);
      }
    }
    w.spawnParticle(Particle.EXPLOSION_NORMAL,origin,40,radius*.35,.3,radius*.35,.12);
    w.playSound(origin,Sound.ENTITY_GENERIC_EXPLODE,.9f,1.45f);
  }
  private void effect(int school,LivingEntity t){switch(school){
    case 0->t.setFireTicks(Math.max(t.getFireTicks(),40));
    case 1->t.addPotionEffect(new PotionEffect(PotionEffectType.SLOW,35,0));
    case 2->t.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,35,0));
    case 3->t.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,35,0));
    case 4->t.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,60,0));
    case 5->t.setVelocity(t.getVelocity().add(new Vector(0,.25,0)));
    case 6->t.addPotionEffect(new PotionEffect(PotionEffectType.SLOW,20,1));
    case 7->t.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,35,0));
  }}
  private void sword(Player p,int slot){Fighter f=get(p);if(f.staminaExhausted)return;if(!f.sword||System.currentTimeMillis()>=f.ultEnd)return;long now=System.currentTimeMillis();
    long ready=f.swordCd[slot];if(now<ready)return;f.swordCd[slot]=now+1000;double dmg=(f.swordPlus?18:14);
    if(slot==0){LivingEntity t=ray(p,6,1);if(t!=null)hit(p,t,dmg);}
    if(slot==1){Location center=p.getLocation();double radius=5+(f.swordPlus?2:0);shockwaveFx(center,radius);
      for(LivingEntity t:nearby(p,center,radius))if(hit(p,t,dmg*.65))shockwaveKnockback(t,center,f.swordPlus?1.8:1.2);}
    if(slot==2){Location at=p.getLocation().add(p.getLocation().getDirection().multiply(3));shockwaveFx(at,4);
      for(LivingEntity t:nearby(p,at,4))if(hit(p,t,dmg*.8))shockwaveKnockback(t,at,f.swordPlus?2.0:1.4);}
    if(slot==3){Vector d=p.getLocation().getDirection().setY(0).normalize();p.setVelocity(d.multiply(1.7));LivingEntity t=ray(p,8,1.1);if(t!=null)hit(p,t,dmg);}
    swordArcFx(p,p.getEyeLocation(),p.getEyeLocation().getDirection(),slot,true);
  }
  private void ultimate(Player p){Fighter f=get(p);if(f.staminaExhausted)return;long now=System.currentTimeMillis();if(f.gauge<100||now<f.locked||f.sword||p.isDead())return;
    f.gauge=0;f.noGain=now+20000;int id=f.ultimate;boolean plus=f.upgraded&&f.ultUses[id]>=20;
    if(!plus&&!nearby(p,p.getLocation(),28).isEmpty()){f.ultUses[id]++;if(f.ultUses[id]==20)p.sendMessage("§6上位ウルト解放: "+ULT_PLUS[id]);save(p);}
    // Preparation is a real stationary phase. Camera rotation is still allowed.
    f.ultChargeEnd=now+1500;f.locked=Math.max(f.locked,now+1500);
    p.setSprinting(false);p.setVelocity(new Vector(0,0,0));
    p.sendTitle("§6§l"+(plus?ULT_PLUS:ULT)[id],"§7ULTIMATE INITIALIZING",3,24,5);
    p.getWorld().playSound(p.getLocation(),Sound.ENTITY_WITHER_SPAWN,.8f,1.5f);
    Bukkit.getScheduler().runTaskLater(this,()->{
      if(!p.isOnline()||p.isDead()||f.staminaExhausted)return;
      f.ultChargeEnd=0;
      executeUltimate(p,f,id,plus);
    },30L);
  }
  private void executeUltimate(Player p,Fighter f,int id,boolean plus){
    long now=System.currentTimeMillis();
    p.getWorld().spawnParticle(Particle.END_ROD,p.getLocation().add(0,1,0),90,1.0,1.2,1.0,.12);
    p.getWorld().spawnParticle(Particle.FIREWORKS_SPARK,p.getLocation().add(0,1,0),70,1.0,1.0,1.0,.14);
    switch(id){
      case 0->{
        // Earthquake / Grand Cross: ground-hugging, one-block-high white and brown shockwave fronts.
        final Location epicenter=p.getLocation().clone();
        final double maxRadius=plus?22.0:18.0;
        final int waves=plus?4:3;
        f.locked=now+5000;
        for(int wave=0;wave<waves;wave++){
          final int waveIndex=wave;
          Bukkit.getScheduler().runTaskLater(this,()->{
            if(!p.isOnline()||p.isDead()||!p.getWorld().equals(epicenter.getWorld()))return;
            animateEarthShockwave(p,epicenter,maxRadius,waveIndex,waves,plus);
          },wave*12L);
        }
      }
      case 1->{f.locked=now+3000;launchMagicOrbs(p,plus);}
      case 2->{f.locked=now+10000;int n=plus?45:40;for(int j=0;j<n;j++){final int idx=j;Bukkit.getScheduler().runTaskLater(this,()->{
        if(!p.isOnline()||p.isDead())return;double angle=rng.nextDouble()*Math.PI*2,rad=Math.sqrt(rng.nextDouble())*25;
        Location spot=p.getLocation().add(Math.cos(angle)*rad,0,Math.sin(angle)*rad);double r=plus&&idx>=40?5:1.5;
        p.getWorld().spawnParticle(Particle.END_ROD,spot,65,r,.6,r,.08);
        p.getWorld().spawnParticle(Particle.FIREWORKS_SPARK,spot,30,r,.3,r,.1);
        Bukkit.getScheduler().runTaskLater(this,()->{for(LivingEntity t:nearby(p,spot,r))if(hit(p,t,plus?(idx>=40?10:6):7))t.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,100,0));},plus&&idx>=40?24L:12L);
      },j*8L);}}
      case 3->{f.locked=now+3000;Bukkit.getScheduler().runTaskLater(this,()->{if(!p.isOnline()||p.isDead())return;
        Location origin=p.getLocation();Vector dir=origin.getDirection().setY(0).normalize();double length=plus?30:25;
        for(LivingEntity t:nearby(p,origin,length)){Vector delta=t.getLocation().toVector().subtract(origin.toVector());double forward=delta.dot(dir);double sideways=delta.clone().subtract(dir.clone().multiply(forward)).length();
          if(forward>=0&&forward<=length&&sideways<=(plus?2.5:1.5)&&p.hasLineOfSight(t))hit(p,t,35);}
        for(double dist=0;dist<length;dist+=2){Location loc=origin.clone().add(dir.clone().multiply(dist));if(!loc.getBlock().isPassable())break;swordArcFx(p,loc.add(0,1,0),dir,3,true);}
        Location dest=origin.clone();for(double dist=1;dist<length;dist+=.5){Location candidate=origin.clone().add(dir.clone().multiply(dist));if(!candidate.getBlock().isPassable()||!candidate.clone().add(0,1,0).getBlock().isPassable())break;dest=candidate;}p.teleport(dest);
      },60L);}
      case 4->{f.counter=true;f.stored=0;f.ultEnd=now+(plus?10000:8000);}
      case 5->{f.timeEnd=now+4000;Location at=p.getLocation();for(LivingEntity t:nearby(p,at,plus?14:12))t.addPotionEffect(new PotionEffect(PotionEffectType.SLOW,80,plus?2:1));
        if(plus)Bukkit.getScheduler().runTaskLater(this,()->{for(LivingEntity t:nearby(p,at,14))t.addPotionEffect(new PotionEffect(PotionEffectType.SLOW,12,5));},80L);}
      case 6->{f.buffEnd=now+8000;p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,160,plus?2:1));}
      case 7->{f.sword=true;f.swordPlus=plus;f.ultEnd=now+10000;equip(p);}
    }
  }
  /** A one-block-tall expanding shockwave; white cloud and brown earth fragments move together. */
  private void animateEarthShockwave(Player caster,Location center,double maxRadius,int waveIndex,int waves,boolean grandCross){
    final World world=center.getWorld();
    final java.util.Set<java.util.UUID> struck=new java.util.HashSet<>();
    final double waveRadius=maxRadius*(waveIndex+1)/waves;
    final org.bukkit.block.data.BlockData earth=Material.DIRT.createBlockData();
    new org.bukkit.scheduler.BukkitRunnable(){
      int tick=0;
      @Override public void run(){
        if(!caster.isOnline()||caster.isDead()||!caster.getWorld().equals(world)){cancel();return;}
        double r=waveRadius*(++tick)/12.0;
        int segments=Math.max(48,(int)(r*13));
        for(int i=0;i<segments;i++){
          double angle=2*Math.PI*i/segments;
          double x=center.getX()+Math.cos(angle)*r,z=center.getZ()+Math.sin(angle)*r;
          // From ankle height to exactly one block above the floor, not a tall explosion column.
          for(int layer=0;layer<3;layer++){
            Location spot=new Location(world,x,center.getY()+.12+layer*.36,z);
            if(i%2==0)world.spawnParticle(Particle.CLOUD,spot,1,0,0,0,0);
            else world.spawnParticle(Particle.BLOCK_CRACK,spot,1,0,0,0,0,earth);
          }
        }
        for(LivingEntity target:nearby(caster,center,waveRadius+1)){
          if(struck.contains(target.getUniqueId()))continue;
          Location at=target.getLocation();
          double dx=at.getX()-center.getX(),dz=at.getZ()-center.getZ();
          double horizontal=Math.sqrt(dx*dx+dz*dz);
          if(Math.abs(horizontal-r)<=1.5&&Math.abs(at.getY()-center.getY())<=1.3){
            struck.add(target.getUniqueId());
            double damage=grandCross?(waveIndex==3?9:7):8;
            if(hit(caster,target,damage))shockwaveKnockback(target,center,grandCross?1.6:1.2);
          }
        }
        if(tick==1)world.playSound(center,Sound.ENTITY_GENERIC_EXPLODE,1.0f,.65f);
        if(tick>=12)cancel();
      }
    }.runTaskTimer(this,0L,1L);
  }
  /** Four / seven independently animated elemental spheres that physically travel into targets. */
  private void launchMagicOrbs(Player caster,boolean seven){
    final int count=seven?7:4;
    final Particle[] elements={Particle.FLAME,Particle.SNOWFLAKE,Particle.ELECTRIC_SPARK,Particle.SOUL_FIRE_FLAME,
        Particle.END_ROD,Particle.PORTAL,Particle.CRIT};
    final Location center=caster.getLocation().clone();
    final List<LivingEntity> targets=nearby(caster,center,23);
    // First materialize the orbiting elemental aggregates around the caster.
    for(int i=0;i<count;i++){
      final int index=i;
      Bukkit.getScheduler().runTaskLater(this,()->{
        if(!caster.isOnline()||caster.isDead())return;
        new org.bukkit.scheduler.BukkitRunnable(){
          int tick=0;
          Location pos;
          LivingEntity target;
          @Override public void run(){
            if(!caster.isOnline()||caster.isDead()||get(caster).staminaExhausted){cancel();return;}
            double angle=2*Math.PI*index/count;
            if(tick<20){
              pos=caster.getLocation().clone().add(Math.cos(angle)*2.1,1.5+Math.sin(tick*.25)*.25,Math.sin(angle)*2.1);
            }else{
              if(tick==20){
                List<LivingEntity> live=nearby(caster,caster.getLocation(),23);
                target=live.isEmpty()?null:live.get(index%live.size());
              }
              Vector direction;
              if(target!=null&&target.isValid()&&target.getWorld().equals(caster.getWorld())){
                direction=target.getLocation().add(0,1,0).toVector().subtract(pos.toVector());
              }else direction=new Vector(Math.cos(angle),.08,Math.sin(angle));
              if(direction.lengthSquared()>.0001){
                Vector travel=direction.normalize().multiply(.85);
                RayTraceResult wall=pos.getWorld().rayTraceBlocks(pos,travel.clone().normalize(),travel.length(),FluidCollisionMode.NEVER,true);
                RayTraceResult hitEntity=pos.getWorld().rayTraceEntities(pos,travel.clone().normalize(),travel.length(),.85,e->enemy(caster,e));
                if(hitEntity!=null&&hitEntity.getHitEntity() instanceof LivingEntity struck){
                  hit(caster,struck,seven?9:11);
                  pos.getWorld().spawnParticle(Particle.EXPLOSION_NORMAL,pos,12,.45,.45,.45,.05);
                  cancel();return;
                }
                if(wall!=null){cancel();return;}
                pos.add(travel);
              }
            }
            pos.getWorld().spawnParticle(elements[index],pos,22,.30,.30,.30,.035);
            pos.getWorld().spawnParticle(Particle.END_ROD,pos,3,.18,.18,.18,.015);
            if(++tick>80)cancel();
          }
        }.runTaskTimer(SkillArenaPlugin.this,0L,1L);
      },i*5L);
    }
  }
  private LivingEntity rayToward(Player p,Location start,LivingEntity t,double range,double radius){Vector d=t.getEyeLocation().toVector().subtract(start.toVector());if(d.lengthSquared()<.001)return null;
    RayTraceResult b=p.getWorld().rayTraceBlocks(start,d.normalize(),range,FluidCollisionMode.NEVER,true);double max=b==null?range:Math.max(0,b.getHitPosition().distance(start.toVector())-.01);
    RayTraceResult h=p.getWorld().rayTraceEntities(start,d.normalize(),max,radius,e->enemy(p,e));return h!=null&&h.getHitEntity() instanceof LivingEntity l?l:null;}
  /** A dedicated healing consumable, limited to PvE encounters and bound to its owner. */
  private ItemStack healingPotion(Player owner){
    ItemStack item=new ItemStack(Material.POTION);
    org.bukkit.inventory.meta.PotionMeta meta=(org.bukkit.inventory.meta.PotionMeta)item.getItemMeta();
    meta.setDisplayName("§d§l探索者の回復薬");
    meta.setColor(org.bukkit.Color.fromRGB(228,68,142));
    meta.setLore(java.util.List.of("§7右クリック：HPを40回復", "§7ボス戦・ダンジョン専用", "§8SkillArena専用品"));
    meta.getPersistentDataContainer().set(new NamespacedKey(this,"healing_potion"),org.bukkit.persistence.PersistentDataType.STRING,owner.getUniqueId().toString());
    item.setItemMeta(meta);return item;
  }
  private boolean isHealingPotion(ItemStack item){return item!=null&&item.getType()==Material.POTION&&item.hasItemMeta()&&item.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(this,"healing_potion"),org.bukkit.persistence.PersistentDataType.STRING);}
  @EventHandler(priority=EventPriority.HIGH)public void drinkHealing(org.bukkit.event.player.PlayerInteractEvent e){
    if(e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND)return;
    org.bukkit.event.block.Action a=e.getAction();if(a!=org.bukkit.event.block.Action.RIGHT_CLICK_AIR&&a!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)return;
    Player p=e.getPlayer();ItemStack item=p.getInventory().getItemInMainHand();if(!isHealingPotion(item))return;
    e.setCancelled(true);
    String owner=item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(this,"healing_potion"),org.bukkit.persistence.PersistentDataType.STRING);
    if(!p.getUniqueId().toString().equals(owner)){p.sendMessage("§cこの回復薬は他のプレイヤー専用です。");return;}
    if(!isPveHealingArea(p)){p.sendMessage("§c回復薬はボス戦・ダンジョン内でのみ使用できます。");return;}
    if(p.isDead()||p.getHealth()>=p.getMaxHealth()){p.sendMessage("§7HPは既に満タンです。");return;}
    long now=System.currentTimeMillis();long until=healingCooldown.getOrDefault(p.getUniqueId(),0L);
    if(now<until){p.sendMessage("§c回復薬の再使用まであと"+((until-now+999)/1000)+"秒");return;}
    healingCooldown.put(p.getUniqueId(),now+12000);
    p.setHealth(Math.min(p.getMaxHealth(),p.getHealth()+40));
    item.setAmount(item.getAmount()-1);
    p.getWorld().spawnParticle(Particle.HEART,p.getLocation().add(0,1,0),12,.5,.7,.5,.02);
    p.getWorld().playSound(p.getLocation(),Sound.ENTITY_GENERIC_DRINK,.8f,1.2f);
    p.sendMessage("§d回復薬を使用しました。 §aHP +40");
  }
  private final java.util.Map<java.util.UUID,Long> healingCooldown=new java.util.HashMap<>();
  private boolean isPveHealingArea(Player p){
    if(vargas!=null&&vargas.isParticipant(p))return true;
    String world=p.getWorld().getName().toLowerCase(java.util.Locale.ROOT);
    return world.contains("dungeon");
  }
  @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
    if(command.getName().equalsIgnoreCase("sa") && args.length>0 && (args[0].equalsIgnoreCase("ultcharge") || args[0].equalsIgnoreCase("gauge"))){
      if(!sender.hasPermission("skillarena.admin")){sender.sendMessage("§c管理者権限が必要です。");return true;}
      Player target;
      if(args.length>=2){
        target=Bukkit.getPlayerExact(args[1]);
        if(target==null){sender.sendMessage("§cプレイヤーがオンラインではありません: "+args[1]);return true;}
      }else if(sender instanceof Player player){target=player;}
      else{sender.sendMessage("§e使用方法: /sa ultcharge <プレイヤー> [0-100]");return true;}
      int amount=100;
      if(args.length>=3){
        try{amount=Integer.parseInt(args[2]);}catch(NumberFormatException ex){sender.sendMessage("§cチャージ量は0～100の整数で指定してください。");return true;}
        if(amount<0||amount>100){sender.sendMessage("§cチャージ量は0～100の範囲です。");return true;}
      }
      get(target).gauge=amount;
      sender.sendMessage("§a"+target.getName()+" のウルトゲージを "+amount+"% に設定しました。");
      if(!sender.equals(target))target.sendMessage("§6ウルトゲージが "+amount+"% に設定されました。");
      return true;
    }
    if(!(sender instanceof Player p)){sender.sendMessage("§e/sa ultcharge <プレイヤー> [0-100]");return true;}
    if(command.getName().equalsIgnoreCase("skills")){menu(p);return true;}
    if(args.length>1&&args[0].equalsIgnoreCase("boss")&&args[1].equalsIgnoreCase("spawn")&&p.hasPermission("skillarena.admin")){vargas.start(p);return true;}
    if(args.length>1&&args[0].equalsIgnoreCase("boss")&&args[1].equalsIgnoreCase("stop")&&p.hasPermission("skillarena.admin")){vargas.end(false);return true;}
    if(args.length>0&&(args[0].equalsIgnoreCase("accessories")||args[0].equalsIgnoreCase("accessory"))){
      if(args.length>=3&&args[1].equalsIgnoreCase("give")&&p.hasPermission("skillarena.admin")){
        try{int id=Integer.parseInt(args[2]);if(!accessories.grant(p,id))p.sendMessage("§c無効なID、または既に所持しています (0～5)");}
        catch(NumberFormatException ex){p.sendMessage("§cIDは0～5で指定してください");}return true;
      }
      accessories.open(p);return true;
    }
    if(args.length>0&&args[0].equalsIgnoreCase("heal")){
      if(!p.hasPermission("skillarena.admin")){p.sendMessage("§c管理者権限が必要です。");return true;}
      int amount=3;
      if(args.length>1)try{amount=Math.max(1,Math.min(64,Integer.parseInt(args[1])));}catch(NumberFormatException ignored){}
      ItemStack pot=healingPotion(p);pot.setAmount(amount);
      java.util.Map<Integer,ItemStack> leftovers=p.getInventory().addItem(pot);
      for(ItemStack extra:leftovers.values())p.getWorld().dropItemNaturally(p.getLocation(),extra);
      p.sendMessage("§d探索者の回復薬 x"+amount+" を配布しました。");return true;
    }
    if(args.length>0&&args[0].equalsIgnoreCase("kit")){equip(p);p.sendMessage("§aSkillArena装備を配布しました");return true;}
    if(args.length>0&&args[0].equalsIgnoreCase("mastery")&&p.hasPermission("skillarena.admin")){for(int i=0;i<16;i++)get(p).mastery[i]=10;save(p);p.sendMessage("§aテスト用に全熟練度を10に設定");return true;}
    p.sendMessage("§e/skills §7/sa kit §7/sa ultcharge [player] [0-100](OP) §7/sa mastery(OP) §7/sa boss spawn|stop(OP) §7/sa heal [個数](OP) §7/sa accessories [give 0-5](OP)");return true;
  }
}
