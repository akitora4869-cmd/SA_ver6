# SkillArena v0.6.0 — Vargas Packet NPC prototype

Paper 1.20.1 / Java 17 / ProtocolLib 5.1.0.

## Setup
1. Install ProtocolLib 5.1.0 compatible with Paper 1.20.1 on the server.
2. Build via GitHub Actions or `mvn clean package`.
3. Put the resulting SkillArena jar in `plugins/` and restart.
4. `/sa boss spawn` starts the existing Vargas boss with a client-side player model.
5. `/sa boss stop` stops the fight and cleans up NPC packets.

## Design
- Hidden zombie remains authoritative for pathing, HP, collision, and skill ray hits.
- ProtocolLib sends player-info, spawn, equipment, teleport, head rotation, arm swing, destroy and remove-player-info packets.
- Each viewer is synchronized independently; join/leave and world changes are handled by the sync loop.
- Optional signed skin textures can be entered in `config.yml` under `vargas.skin`. Without these values, a default skin is used.
- Client-side interactions are intercepted for basic melee testing; attack classification and anti-cheat integration require further hardening.

## Current limitations
- This is a **prototype**. The Packet NPC has not been compiled or tested against a live Paper 1.20.1 server here.
- Client-side player movement currently uses frequent teleport packets, which may look less smooth than natural interpolation.
- Native player attack animations are limited; custom drawing/sheathe animations are deferred.
- No skin texture/signature is shipped.
- The existing boss skill and AI implementations remain prototypes.


## v0.6.1 Maven dependency fix
ProtocolLib 5.1.0 is resolved from Maven Central as `net.dmulloy2:ProtocolLib:5.1.0` with `provided` scope. Do not commit ProtocolLib.jar to this repository. Install ProtocolLib 5.1.0 separately in the server `plugins/` directory.


## v0.6.2
- Fixed Paper 1.20.1 Particle.ENCHANTMENT_TABLE constant.
- Resolved java.util.Vector vs Bukkit Vector import conflict.
- Used ProtocolLib 5.1.0 PLAYER_INFO packet constant.
- GitHub Actions Maven compilation still needs verification.

## v0.6.4
- Fixed missing `org.bukkit.util.RayTraceResult` import (five compiler errors).

### v0.6.4 build fix
- PacketVargasVisual.send: catch RuntimeException instead of checked InvocationTargetException, which the current ProtocolLib API does not declare.

## v0.7.0
- Vargas boss bar displays current/max HP numerically; damage is tracked as virtual HP, avoiding vanilla attribute limits.
- More dramatic skill trails, circles, impact bursts, ultimate particles and sound.
- Visual effects do not alter skill damage or cooldowns.

## v0.7.1 - Player HP display
- Player max HP is 100 (actual Bukkit health).
- Action bar shows HP current/max, MP, ultimate charge and skill cooldowns.
- Vanilla hearts are scaled to ten hearts for a compact visual gauge; numeric HP is authoritative.
- Health scale is set on login and plugin enable.

## v0.7.2 管理者ウルトチャージ
- `/sa ultcharge` : 自分のウルトを100%にする（OP権限）
- `/sa ultcharge <プレイヤー>` : 対象を100%にする（コンソール可）
- `/sa ultcharge <プレイヤー> <0-100>` : 対象のゲージを指定値に設定
- `/sa gauge` : 従来のコマンド互換（自分を100%）
- 権限: `skillarena.admin`


## v0.7.3 Visual combat
- Family-0 elemental projectiles move tick-by-tick with collision and delayed damage.
- Fireball: dense flame core, lava embers and sparks. Other schools have distinct trails.
- Martial attacks and sword skills use curved slash ribbons.
- Damage, MP and cooldown values are unchanged.

## v0.7.4
- 衝撃拳、ブラスト、テンペスト、アースクエイク、ヴァルガスの衝撃波にウィンドチャージ風の水平＋垂直ノックバックを追加。
- 衝撃波の着弾地点に風圧リングと爆風のパーティクル・効果音を追加。
- 強化版の吹き飛ばしを強め、最大速度を制限。

## v0.8.0 - Stamina
- Food bar is now stamina (20 = 100%). Natural hunger changes are disabled.
- Sprinting drains 0.22 stamina per 5 ticks; normal recovery is 0.16 per 5 ticks, sneaking 0.28.
- While stamina remains, walk speed is 0.32 (vanilla 0.2) and Jump Boost I is applied.
- At zero stamina, speed returns to 0.2 and sprinting is stopped.
- HUD shows ST percent; stamina resets on join and is not persisted.


## v0.8.1 スタミナ切れの行動不能
- STが0になった瞬間、移動・ジャンプ不可。視点操作は可能。
- スタミナが回復途中でも移動不可を維持し、100%に戻った時だけ解除。
- スタミナ切れ中は移動速度0、水平・垂直移動をイベントで阻止。
- 回復速度は従来通り（停止状態で5tickごとに0.16/20回復）。
- HUDに「ST 回復中」を表示し、枯渇・全回復時にメッセージを表示。
- 管理者のテレポート等の位置変更イベントは移動イベントと別扱い。


## v0.8.2 — Stamina exhaustion
- When stamina reaches 0, exhaustion lasts until the meter returns to 20/20.
- Recovery during exhaustion: +0.50 food units every 5 ticks (40 updates, approximately 10 seconds).
- Exhausted players cannot move, jump, use skills, sword skills, ultimates, or Break Step/dodge.
- Existing dodge i-frames, counter stance and speed buff are removed on exhaustion; no defensive invulnerability applies during exhaustion.
- Camera rotation and skill GUI access remain available.


## v0.8.3 — OVERHEAT UI
- Replace the Japanese exhaustion label with red bold `OVERHEAT` in the action bar, retaining the recovery percentage.
- On reaching zero stamina, show `OVERHEAT / SYSTEM LOCKED` title, warning sound and chat message.
- At full recovery (approximately 10 seconds), show `SYSTEM READY` title, confirmation sound and chat message.
- No changes to stamina recovery rate or defensive restrictions.


## v0.8.4
- Fixed an extra closing brace in the shockwave ultimate (SkillArenaPlugin.java), which caused 51 cascading Java syntax errors in v0.8.3.

## v0.8.5
- Food level 6 is treated as stamina 0%; the usable stamina range is food 6-20.
- OVERHEAT begins at food level 6, with a fixed approximately 10-second recovery to food level 20.
- Sprint drain and idle/sneak recovery are proportionally adjusted for the new 14-point usable range.
- All player skill, sword technique, and ultimate damage applied via `hit` is increased by 50%; boss damage unchanged.


## v0.8.6 — ヴァルガス外見・アクセサリ
- 剣帝ヴァルガスはネザライト防具・剣を装備した通常のゾンビモデルを使用（Packet fake-player表示を使わない）。
- ネザライト防具の防御値をボスHP計算に反映しない（防具は外見のみ）。
- `/sa accessories` またはスキル構築GUIのアクセサリボタンから専用4スロット装備GUIを開く。
- アクセサリ6種。所持済みアクセサリをクリックで空きスロットに装備、装備枠クリックで解除。所持・装備状態はプレイヤーYAMLへ永続化。
- 効果はヴァルガス戦の参加者か、将来のダンジョンAPIで指定されたプレイヤーにのみ適用。通常PvPでは無効。
- ボス討伐時、参加者へ未所持アクセサリを1種ランダム配布。テスト用 `/sa accessories give <0-5>` (OP)。

## v0.8.7
- ウルト発動前1.5秒は位置移動不能（視点変更可）。発動演出の後に技を実行。
- フォースマジックは4属性、セブンスマジックは7属性の球体を周囲に生成し、敵へ飛翔・接触時にダメージ。遮蔽物で消滅。

## v0.8.8
アースクエイク／グランドクロス：地面から高さ約1ブロックの白い雲と茶色い土片の混合衝撃波が、リング状に外へ広がる演出に変更。衝撃波が接触した敵にダメージとノックバックを与える。


## v0.8.9 ボス機動AI
- ヴァルガスは速度ベクトルによって追跡・横移動・距離調整します。
- 遠距離でジャンプ突進し、ジェノサイドは跳躍ダッシュします。
- プレイヤーの飛翔スキルが接近したとき、確率・クールダウン付きで左右に回避します。
- 攻撃予告中は移動を抑制し、攻撃の視認性を維持します。


## v0.9.0: 標準機動アクション
- スキルを装備せずに空中でジャンプキーをもう一度押すと二段ジャンプ（ST 2/14、着地で再使用可能）。
- スニークキーを0.09～0.35秒間隔で二度押すと短距離高速ダッシュ（ST 1.5/14、CT 1.8秒）。
- OVERHEAT中・ウルト詠唱中・行動ロック中は両方使用不可。
- ブレイクステップ（スニーク＋左クリック）とは独立したアクション。
- サバイバル・アドベンチャーモード限定。


## v0.9.1 — 剣帝ヴァルガス分身
フェーズ2移行時にHP400のネザライト装備ゾンビ分身を1体召喚。追跡・横移動・跳躍突進・簡易飛翔攻撃回避・BREAK/BLAST/TEMPEST/GENOCIDE系の弱化攻撃を行う。分身はボス本体とHPを共有せず、倒しても追加報酬なし。ボス戦終了時は自動消去。


## v0.9.2 進化スキル修正
- 「・連」（tier 2）は3回の時差攻撃（4tick間隔）。合計威力を3分割し、各段でダメージ・パーティクル・斬撃音を処理。飛翔弾は着弾後に連撃。
- 連撃1発の使用で熟練度・コンボは1回だけ加算。
- 熟練度の進捗を技能系統ごとに独立管理・永続化（従来は全系統共通カウンタ）。
- 転位の移動だけで攻撃熟練度が増えないよう修正。
- ビルドと実機での動作検証は別途必要。

## v0.9.3 Mobility speed tuning
- With stamina, normal walk speed is now 0.37 (previously 0.32).
- Sprint walk-speed setting is 0.44 (previously 0.32), in addition to Minecraft sprint multiplier.
- OVERHEAT movement lock and 10-second recovery remain unchanged.
- Speed accessory bonuses still apply in boss and dungeon encounters.


## v0.9.4
- プレイヤーの落下ダメージ無効。
- `/sa heal [個数]` (OP) で専用回復薬を配布。右クリックでHP40回復、12秒CT、ボス戦参加者またはワールド名に `dungeon` を含むダンジョン内限定。
- 剣帝ヴァルガスと分身の戦闘移動AIを改善。追跡、回り込み、距離調整、予告中の微移動を追加。
