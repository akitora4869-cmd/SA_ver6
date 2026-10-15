package jp.skillarena.boss;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import java.util.*;

/** Server-authoritative Vargas combat prototype. A packet player visual can replace the fallback entity. */
public final class VargasBoss implements Listener {
  private final JavaPlugin plugin;
  private final Random random = new Random();
  private Zombie body;
  private final Map<UUID, Echo> echoes = new HashMap<>();
  private static final class Echo {
    final Zombie zombie; double hp = 400; long nextMove = 0, nextLeap = 0;
    Echo(Zombie zombie){this.zombie=zombie;}
  }
  private PacketVargasVisual visual;
  private BossBar bar;
  private BukkitTask ticker;
  private final Set<UUID> party = new HashSet<>();
  private final Map<UUID,Long> recentThreat = new HashMap<>();
  private final Map<UUID,Long> dodgeUntil = new HashMap<>();
  private double maxHp, currentHp;
  private int phase, ticks, fightTicks;
  private long nextAction, recoveryUntil, nextEvade, nextLeap, nextStrafeChange;
  private int strafeDirection=1;
  private String currentMove = "";
  private boolean pending;
  private final Map<String,Long> cooldowns = new HashMap<>();

  public VargasBoss(JavaPlugin plugin){this.plugin=plugin;Bukkit.getPluginManager().registerEvents(this,plugin);}
  public boolean active(){return body!=null&&body.isValid()&&!body.isDead();}
  public boolean isParticipant(Player player){return active()&&party.contains(player.getUniqueId());}
  public void start(Player leader){
    if(active()){leader.sendMessage("§c剣帝ヴァルガスは既に出現しています。");return;}
    World world=leader.getWorld();Location spawn=leader.getLocation().clone().add(leader.getLocation().getDirection().setY(0).normalize().multiply(9));
    spawn.setY(world.getHighestBlockYAt(spawn)+1);
    party.clear();party.add(leader.getUniqueId());for(Player p:world.getPlayers())if(p.getGameMode()!=GameMode.SPECTATOR&&p.getLocation().distanceSquared(leader.getLocation())<=24*24&&party.size()<4)party.add(p.getUniqueId());
    int size=party.size();double scale=new double[]{0,1,1.75,2.4,3}[size];maxHp=2000*scale;currentHp=maxHp;
    body=world.spawn(spawn,Zombie.class,z->{z.setAdult();z.setAI(false);z.setGravity(true);z.setSilent(true);z.setRemoveWhenFarAway(false);z.setCanPickupItems(false);z.setCustomName("§4剣帝ヴァルガス");z.setCustomNameVisible(true);z.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20);z.setHealth(20);z.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(.32);z.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));z.getEquipment().setHelmet(new ItemStack(Material.NETHERITE_HELMET));z.getEquipment().setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));z.getEquipment().setLeggings(new ItemStack(Material.NETHERITE_LEGGINGS));z.getEquipment().setBoots(new ItemStack(Material.NETHERITE_BOOTS));});
    // Use the real netherite-armored zombie model. No packet-based fake player visual.
    visual=null;
    clearEchoes();phase=1;ticks=0;fightTicks=0;nextAction=0;recoveryUntil=0;nextEvade=0;nextLeap=0;nextStrafeChange=0;strafeDirection=1;pending=false;cooldowns.clear();dodgeUntil.clear();recentThreat.clear();
    bar=Bukkit.createBossBar("§4剣帝ヴァルガス §7| PHASE 1 §f"+(int)maxHp+"/"+(int)maxHp,BarColor.RED,BarStyle.SEGMENTED_10);
    for(UUID id:party){Player p=Bukkit.getPlayer(id);if(p!=null){bar.addPlayer(p);p.sendTitle("§4§l剣帝ヴァルガス","§7挑戦開始",10,45,15);}}
    ticker=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);
  }
  private List<Player> players(){List<Player> out=new ArrayList<>();if(body==null)return out;for(UUID id:party){Player p=Bukkit.getPlayer(id);if(p!=null&&p.isOnline()&&!p.isDead()&&p.getWorld()==body.getWorld()&&p.getGameMode()!=GameMode.SPECTATOR&&p.getLocation().distanceSquared(body.getLocation())<65*65)out.add(p);}return out;}
  private Player target(){List<Player> ps=players();if(ps.isEmpty())return null;ps.sort(Comparator.comparingDouble(p->p.getLocation().distanceSquared(body.getLocation())-(recentThreat.getOrDefault(p.getUniqueId(),0L)>System.currentTimeMillis()?16:0)));return ps.get(0);}
  private void tick(){if(!active()){end(false);return;}fightTicks++;ticks++;if(fightTicks>=12000||players().isEmpty()){end(false);return;}
    double ratio=currentHp/maxHp;int next=ratio<=.20?3:ratio<=.50?2:1;
    if(next>phase){int previous=phase;phase=next;recoveryUntil=ticks+35;pending=false;nextAction=ticks+45;body.getWorld().strikeLightningEffect(body.getLocation());body.getWorld().playSound(body.getLocation(),Sound.ENTITY_WITHER_SPAWN,1,.8f);for(Player p:players())p.sendTitle("§4PHASE "+phase,phase==3?"§cラグナロク解放":"§c上位剣技解放",5,35,10);if(previous<2)spawnEchoes();}
    bar.setProgress(Math.max(0,Math.min(1,ratio)));bar.setTitle("§4剣帝ヴァルガス §7| PHASE "+phase+" §f"+(int)Math.ceil(currentHp)+"/"+(int)maxHp);
    if(ticks%2!=0)return;
    tickEchoes();
    Player t=target();if(t==null)return;
    Location at=body.getLocation();Vector toward=t.getLocation().toVector().subtract(at.toVector());double distance=toward.length();if(distance<.1)return;
    Vector flat=toward.clone().setY(0);if(flat.lengthSquared()<.01)return;flat.normalize();
    body.setRotation((float)Math.toDegrees(Math.atan2(-flat.getX(),flat.getZ())),0);
    if(ticks<recoveryUntil||pending){
      if(pending && distance>2){Vector side=new Vector(-flat.getZ(),0,flat.getX()).multiply(strafeDirection*.13);move(body,side);}
      else body.setVelocity(new Vector(0,body.getVelocity().getY(),0));
      return;
    }
    // Combat AI: continuously orbit the target, close gaps and retreat from point-blank range.
    if(ticks>=nextStrafeChange){strafeDirection=random.nextBoolean()?1:-1;nextStrafeChange=ticks+24+random.nextInt(36);}
    Vector side=new Vector(-flat.getZ(),0,flat.getX()).multiply(strafeDirection);
    Vector stride;
    if(distance>6.5)stride=flat.clone().multiply(.38).add(side.clone().multiply(.13));
    else if(distance>3.0)stride=flat.clone().multiply(.18).add(side.clone().multiply(.27));
    else if(distance>1.7)stride=side.clone().multiply(.32);
    else stride=flat.clone().multiply(-.22).add(side.clone().multiply(.24));
    move(body,stride);
    // Leap toward a distant player, including a small vertical impulse for visible jump attacks.
    if(distance>7&&distance<19&&ticks>=nextLeap&&body.isOnGround()&&random.nextDouble()<.11){
      nextLeap=ticks+90;Vector jump=flat.clone().multiply(1.02);jump.setY(.57);
      body.setVelocity(jump);body.getWorld().spawnParticle(Particle.CLOUD,at.clone().add(0,.2,0),24,.5,.1,.5,.05);
      body.getWorld().playSound(at,Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR,1,.85f);
    }
    if(ticks<nextAction)return;
    String move=choose(distance);if(move==null)return;startMove(move,t);
  }
  /** Move with real velocity so the zombie visibly walks instead of teleporting each step. */
  private void move(Zombie z,Vector delta){
    Location at=z.getLocation();Location ahead=at.clone().add(delta.clone().normalize().multiply(.65));
    if(!ahead.getBlock().isPassable()||!ahead.clone().add(0,1,0).getBlock().isPassable()){
      if(z.isOnGround()&&ahead.clone().add(0,1,0).getBlock().isPassable()&&ahead.clone().add(0,2,0).getBlock().isPassable()){
        z.setVelocity(new Vector(delta.getX(),.43,delta.getZ()));
      }
      return;
    }
    if(!ahead.clone().subtract(0,1,0).getBlock().isSolid()&&!ahead.clone().subtract(0,2,0).getBlock().isSolid())return;
    Vector old=z.getVelocity();z.setVelocity(new Vector(delta.getX(),old.getY(),delta.getZ()));
  }
  /** Called by the player's projectile simulation before collision. Cooldown and chance prevent perfect dodges. */
  public void warnProjectile(Player attacker,Location projectile,Vector velocity){
    if(active()&&party.contains(attacker.getUniqueId()))for(Echo e:echoes.values()){
      Zombie z=e.zombie;if(!z.isValid()||ticks<e.nextLeap||z.getWorld()!=projectile.getWorld())continue;
      Vector incoming=z.getLocation().clone().add(0,1,0).toVector().subtract(projectile.toVector());
      if(incoming.lengthSquared()>25||incoming.lengthSquared()<.01)continue;
      Vector direction=velocity.clone();if(direction.lengthSquared()<.001)continue;direction.normalize();
      double along=incoming.dot(direction);
      if(along>0&&along<5&&incoming.subtract(direction.multiply(along)).lengthSquared()<2&&random.nextDouble()<.28){
        e.nextLeap=ticks+85;Vector side=new Vector(-velocity.getZ(),0,velocity.getX());
        if(side.lengthSquared()>.01){side.normalize().multiply(random.nextBoolean()?.65:-.65).setY(.3);z.setVelocity(side);}
      }
    }
    if(!active()||pending||ticks<recoveryUntil||ticks<nextEvade||!party.contains(attacker.getUniqueId()))return;
    if(!body.getWorld().equals(projectile.getWorld()))return;
    Vector incoming=body.getLocation().clone().add(0,1,0).toVector().subtract(projectile.toVector());
    if(incoming.lengthSquared()>49||incoming.lengthSquared()<.01)return;
    Vector travel=velocity.clone().normalize();
    double forward=incoming.dot(travel);
    if(forward<0||forward>7||incoming.clone().subtract(travel.multiply(forward)).lengthSquared()>2.25)return;
    nextEvade=ticks+65;
    if(random.nextDouble()>.55)return;
    Vector side=new Vector(-velocity.getZ(),0,velocity.getX());
    if(side.lengthSquared()<.01)return;
    side.normalize().multiply(random.nextBoolean()?1:-1);
    Location at=body.getLocation();
    Location landing=at.clone().add(side.clone().multiply(2.2));
    if(!landing.getBlock().isPassable()||!landing.clone().add(0,1,0).getBlock().isPassable())side.multiply(-1);
    Vector evade=side.multiply(.95);evade.setY(body.isOnGround()?.34:body.getVelocity().getY());
    body.setVelocity(evade);
    body.getWorld().spawnParticle(Particle.CLOUD,at.clone().add(0,.8,0),22,.4,.5,.4,.04);
    body.getWorld().playSound(at,Sound.ENTITY_ENDERMAN_TELEPORT,.6f,1.7f);
  }
  /** Phase 2: a weaker armored echo uses the same pursuit, leap, evasion and sword-move vocabulary. */
  private void spawnEchoes(){
    if(!active()||!echoes.isEmpty())return;
    Location center=body.getLocation();World w=center.getWorld();
    Location spawn=center.clone().add(2.5,0,1.5);
    if(!spawn.getBlock().isPassable()||!spawn.clone().add(0,1,0).getBlock().isPassable())spawn=center.clone().add(0,0,2.5);
    Zombie z=w.spawn(spawn,Zombie.class,m->{
      m.setAdult();m.setAI(false);m.setSilent(true);m.setRemoveWhenFarAway(false);m.setCanPickupItems(false);
      m.setCustomName("§7剣帝の分身 §f400/400");m.setCustomNameVisible(true);
      m.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20);m.setHealth(20);
      m.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
      m.getEquipment().setHelmet(new ItemStack(Material.NETHERITE_HELMET));
      m.getEquipment().setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
      m.getEquipment().setLeggings(new ItemStack(Material.NETHERITE_LEGGINGS));
      m.getEquipment().setBoots(new ItemStack(Material.NETHERITE_BOOTS));
    });
    echoes.put(z.getUniqueId(),new Echo(z));
    w.spawnParticle(Particle.SOUL,spawn.clone().add(0,1,0),100,.8,1,.8,.08);
    w.playSound(spawn,Sound.ENTITY_ILLUSIONER_MIRROR_MOVE,1,.7f);
    for(Player p:players())p.sendMessage("§8[剣帝] §7『影よ、我が剣を写せ』 §c— 分身が出現した！");
  }
  private void clearEchoes(){for(Echo e:echoes.values())if(e.zombie.isValid())e.zombie.remove();echoes.clear();}
  private void tickEchoes(){
    for(Echo e:new ArrayList<>(echoes.values())){
      Zombie z=e.zombie;if(!z.isValid()||z.isDead()){echoes.remove(z.getUniqueId());continue;}
      Player t=null;double best=Double.MAX_VALUE;
      for(Player p:players()){double d=p.getLocation().distanceSquared(z.getLocation());if(d<best){best=d;t=p;}}
      if(t==null)continue;
      Vector to=t.getLocation().toVector().subtract(z.getLocation().toVector()).setY(0);
      double distance=to.length();if(distance<.1)continue;Vector dir=to.normalize();
      z.setRotation((float)Math.toDegrees(Math.atan2(-dir.getX(),dir.getZ())),0);
      Vector side=new Vector(-dir.getZ(),0,dir.getX()).multiply((ticks/34)%2==0?1:-1);
      if(distance>5.5)move(z,dir.clone().multiply(.27).add(side.clone().multiply(.09)));
      else if(distance>2.5)move(z,dir.clone().multiply(.11).add(side.clone().multiply(.21)));
      else move(z,side.multiply(.24).add(dir.multiply(-.09)));
      if(distance>6&&distance<15&&ticks>=e.nextLeap&&z.isOnGround()&&random.nextDouble()<.08){
        e.nextLeap=ticks+115;z.setVelocity(dir.clone().multiply(.85).setY(.48));
        z.getWorld().spawnParticle(Particle.CLOUD,z.getLocation(),14,.35,.15,.35,.04);
      }
      if(ticks<e.nextMove)continue;
      String attack=distance<5?(random.nextBoolean()?"BREAK":"BLAST"):(distance<9?"TEMPEST":"GENOCIDE");
      e.nextMove=ticks+65+random.nextInt(36);final Player victim=t;final String selected=attack;
      Location telegraph=z.getLocation().clone();z.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,telegraph.clone().add(0,1,0),22,.7,.5,.7,.02);
      z.getWorld().playSound(telegraph,Sound.ITEM_TRIDENT_RIPTIDE_1,.5f,1.3f);
      Bukkit.getScheduler().runTaskLater(plugin,()->{
        if(!active()||!z.isValid()||z.isDead()||!echoes.containsKey(z.getUniqueId())||!victim.isOnline())return;
        Location at=z.getLocation();Vector facing=victim.getLocation().toVector().subtract(at.toVector()).setY(0);
        if(facing.lengthSquared()<.01)return;facing.normalize();
        if(selected.equals("GENOCIDE"))z.setVelocity(facing.clone().multiply(1.15).setY(.3));
        z.getWorld().spawnParticle(selected.equals("BLAST")||selected.equals("TEMPEST")?Particle.CLOUD:Particle.SWEEP_ATTACK,at.clone().add(0,1,0),35,1,.5,1,.08);
        double radius=selected.equals("TEMPEST")?5:selected.equals("BLAST")?4:3;
        for(Player p:players()){
          Vector delta=p.getLocation().toVector().subtract(at.toVector()).setY(0);
          double along=delta.dot(facing);double lateral=delta.clone().subtract(facing.clone().multiply(along)).length();
          boolean hit=selected.equals("BLAST")||selected.equals("TEMPEST")?delta.length()<=radius:along>=0&&along< (selected.equals("GENOCIDE")?10:3.5)&&lateral<1.6;
          if(hit&&System.currentTimeMillis()>=dodgeUntil.getOrDefault(p.getUniqueId(),0L)){
            p.damage(5.0,z); // deliberately below the main boss's 10-24 damage
            if(selected.equals("BLAST")||selected.equals("TEMPEST")){
              Vector away=delta.lengthSquared()<.01?facing.clone():delta.normalize();
              p.setVelocity(away.multiply(.65).setY(.35));
            }
          }
        }
      },12);
    }
  }
  private String choose(double distance){List<String> options=new ArrayList<>();options.add("BREAK");if(distance<6)options.add("BLAST");if(distance<9)options.add("TEMPEST");if(distance>4&&distance<16)options.add("GENOCIDE");if(phase==3&&ticks>160)options.add("RAGNAROK");Collections.shuffle(options,random);for(String s:options)if(cooldowns.getOrDefault(s,0L)<=ticks)return s;return null;}
  private void startMove(String name,Player target){currentMove=name;pending=true;int telegraph=name.equals("RAGNAROK")?40:name.equals("GENOCIDE")?16:12;
    long ct=switch(name){case "BREAK"->38;case "BLAST"->65;case "TEMPEST"->90;case "GENOCIDE"->75;default->240;};cooldowns.put(name,(long)ticks+ct);
    nextAction=ticks+telegraph+12;recoveryUntil=ticks+telegraph+12;
    Location origin=body.getLocation().clone();Vector dir=target.getLocation().toVector().subtract(origin.toVector()).setY(0).normalize();
    double range=name.equals("GENOCIDE")?13:name.equals("RAGNAROK")?14:name.equals("TEMPEST")?6:name.equals("BLAST")?5:4;
    Particle particle=phase>=2?Particle.SOUL_FIRE_FLAME:Particle.CRIT;
    body.getWorld().playSound(origin,Sound.ITEM_TRIDENT_RIPTIDE_1,.8f,.65f);
    for(int j=0;j<telegraph;j+=3){int step=j;Bukkit.getScheduler().runTaskLater(plugin,()->{if(!active()||!pending||!currentMove.equals(name))return;Location at=body.getLocation();for(double d=1;d<range;d+=1.3){Location point=at.clone().add(dir.clone().multiply(d)).add(0,.25,0);body.getWorld().spawnParticle(particle,point,7,.3,.2,.3,.025);}},step);}
    Bukkit.getScheduler().runTaskLater(plugin,()->{if(!active()||!pending||!currentMove.equals(name))return;pending=false;execute(name,dir);},telegraph);
  }
  private void execute(String move,Vector dir){if(visual!=null)visual.swing();Location origin=body.getLocation().clone();World w=origin.getWorld();
    w.playSound(origin,Sound.ENTITY_PLAYER_ATTACK_SWEEP,1.6f,.6f);
    w.spawnParticle(Particle.CRIT,origin.clone().add(0,1,0),85,2,.8,2,.15);
    w.spawnParticle(Particle.SOUL_FIRE_FLAME,origin.clone().add(0,1,0),55,2,.8,2,.08);
    double damage=phase==1?10:phase==2?13:16;
    if(move.equals("RAGNAROK")){w.strikeLightningEffect(origin);damage=24;}
    if(move.equals("GENOCIDE")){body.setVelocity(dir.clone().multiply(1.65).setY(.42));w.spawnParticle(Particle.SWEEP_ATTACK,body.getLocation().add(0,1,0),18,1,.4,1,0);}
    else if(move.equals("TEMPEST")){w.spawnParticle(Particle.EXPLOSION_LARGE,origin.clone().add(0,1,0),3,1,.3,1,0);}
    if(move.equals("BLAST")||move.equals("TEMPEST")||move.equals("RAGNAROK")){
      double radius=move.equals("RAGNAROK")?14:move.equals("TEMPEST")?6:5;
      for(int i=0;i<60;i++){double angle=i*Math.PI/30;
        w.spawnParticle(Particle.CLOUD,origin.clone().add(Math.cos(angle)*radius,.4,Math.sin(angle)*radius),3,.1,.15,.1,.03);}
      w.spawnParticle(Particle.EXPLOSION_NORMAL,origin.clone().add(0,1,0),75,1,.5,1,.13);
      w.playSound(origin,Sound.ENTITY_GENERIC_EXPLODE,1.1f,.85f);
    }
    else w.spawnParticle(Particle.SWEEP_ATTACK,origin.clone().add(0,1,0),22,2,.5,2,.04);
    Set<UUID> struck=new HashSet<>();for(Player p:players()){
      Vector delta=p.getLocation().toVector().subtract(origin.toVector()).setY(0);double along=delta.dot(dir);double sideways=delta.clone().subtract(dir.clone().multiply(along)).length();
      boolean hit=switch(move){case "BREAK"->along>=0&&along<=4&&sideways<1.5;case "BLAST"->delta.length()<5;case "TEMPEST"->delta.length()<6;case "GENOCIDE"->along>=0&&along<14&&sideways<2;default->delta.length()<14;};
      if(!hit||!struck.add(p.getUniqueId())||System.currentTimeMillis()<dodgeUntil.getOrDefault(p.getUniqueId(),0L))continue;
      if(!p.hasLineOfSight(body)&&!move.equals("TEMPEST"))continue;
      p.damage(damage,body);
      if(move.equals("BLAST")||move.equals("TEMPEST")||move.equals("RAGNAROK")){
        Vector away=p.getLocation().toVector().subtract(origin.toVector()).setY(0);
        if(away.lengthSquared()<.0001)away=dir.clone();
        away.normalize();double force=move.equals("RAGNAROK")?1.55:move.equals("TEMPEST")?1.25:1.05;
        p.setVelocity(new Vector(away.getX()*force,move.equals("RAGNAROK")?.85:.65,away.getZ()*force));
      }
      if(move.equals("BLAST")||move.equals("RAGNAROK"))p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW,30,1));
    }
  }
  public void registerDodge(Player p,long until){dodgeUntil.put(p.getUniqueId(),until);}
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void hurt(EntityDamageEvent event){
    Echo echo=echoes.get(event.getEntity().getUniqueId());
    if(echo!=null){
      event.setCancelled(true);
      if(!(event instanceof EntityDamageByEntityEvent by))return;
      Entity source=by.getDamager();
      if(source instanceof Projectile projectile&&projectile.getShooter() instanceof Player shooter)source=shooter;
      if(!(source instanceof Player player)||!party.contains(player.getUniqueId()))return;
      echo.hp=Math.max(0,echo.hp-Math.max(0,event.getDamage()));
      if(echo.hp<=0){Location at=echo.zombie.getLocation();echoes.remove(echo.zombie.getUniqueId());echo.zombie.remove();at.getWorld().spawnParticle(Particle.SOUL,at.add(0,1,0),80,.8,.8,.8,.08);at.getWorld().playSound(at,Sound.ENTITY_WITHER_HURT,1,1.5f);}
      else echo.zombie.setCustomName("§7剣帝の分身 §f"+(int)Math.ceil(echo.hp)+"/400");
      return;
    }
    if(body==null||!event.getEntity().getUniqueId().equals(body.getUniqueId()))return;
    if(!(event instanceof EntityDamageByEntityEvent)){event.setCancelled(true);return;}
    if(event instanceof EntityDamageByEntityEvent by){Entity source=by.getDamager();if(source instanceof Projectile projectile&&projectile.getShooter() instanceof Player shooter)source=shooter;
      if(source instanceof Player p){if(!party.contains(p.getUniqueId())){event.setCancelled(true);return;}recentThreat.put(p.getUniqueId(),System.currentTimeMillis()+4000);}}
    event.setCancelled(true);
    // Read pre-armor damage: netherite is purely cosmetic for Vargas.
    double amount=Math.max(0,event.getDamage());
    if(amount<=0)return;
    currentHp=Math.max(0,currentHp-amount);
    if(currentHp<=0)end(true);
  }
  @EventHandler public void death(EntityDeathEvent e){if(echoes.containsKey(e.getEntity().getUniqueId())){echoes.remove(e.getEntity().getUniqueId());e.getDrops().clear();return;}if(body!=null&&e.getEntity().getUniqueId().equals(body.getUniqueId())){e.getDrops().clear();end(true);}}
  @EventHandler public void quit(PlayerQuitEvent e){if(bar!=null)bar.removePlayer(e.getPlayer());}
  public void end(boolean win){clearEchoes();if(visual!=null){visual.close();visual=null;}if(ticker!=null){ticker.cancel();ticker=null;}if(bar!=null){bar.removeAll();bar=null;}if(body!=null){Location at=body.getLocation();if(win){at.getWorld().playSound(at,Sound.UI_TOAST_CHALLENGE_COMPLETE,1,1);for(UUID id:party){Player p=Bukkit.getPlayer(id);if(p!=null){p.sendTitle("§6討伐成功","§e剣帝ヴァルガス",10,55,15);if(plugin instanceof jp.skillarena.SkillArenaPlugin arena)arena.rewardBossAccessory(p);}}}else for(UUID id:party){Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage("§cヴァルガス戦終了（敗北・時間切れ）");}body.remove();body=null;}pending=false;party.clear();}
}
