package com.one_studio
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.TypedActionResult
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.math.MathHelper
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.jvm.JvmStatic
import org.slf4j.LoggerFactory
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
private data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
fun add(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
fun sub(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
fun mul(s: Float) = Vec3(x * s, y * s, z * s)
}
private class CameraSpring(st: Vec3, aimSpeed: Float = 0.5f) {
var ps = st
private set
private var vc = Vec3()
private val stf = aimSpeed * 120f + 1f
private val dmp = 2f * sqrt(stf) * 1.0f
fun update(target: Vec3, dt: Float) {
val tt = target.sub(ps)
val ax = tt.mul(stf).sub(vc.mul(dmp))
vc = vc.add(ax.mul(dt))
ps = ps.add(vc.mul(dt))
}
}
private class LockOnCamera(st: Vec3, sy: Float = 0f, sp: Float = 0f, aimSpeed: Float = 0.5f) {
private val cma = CameraSpring(st, aimSpeed)
var yw = sy
private set
var pc = sp
private set
private val rsp = (aimSpeed * 40f).coerceAtLeast(0.01f)
fun update(pp: Vec3, tp: Vec3, tv: Vec3, dt: Float) {
val pd = predictTarget(tp, tv)
cma.update(pd, dt)
val dn = cma.ps.sub(pp)
val ty = Math.toDegrees(Math.atan2(dn.z.toDouble(), dn.x.toDouble())).toFloat() - 90f
val tq = -Math.toDegrees(
Math.atan2(dn.y.toDouble(), sqrt((dn.x * dn.x + dn.z * dn.z).toDouble()))
).toFloat()
yw = dampAngle(yw, ty, dt)
pc = dampAngle(pc, tq, dt)
}
companion object {
private const val pdt = 0.08f
fun predictTarget(tp: Vec3, tv: Vec3) = tp.add(tv.mul(pdt))
}
private fun dampAngle(current: Float, target: Float, dt: Float): Float {
var df = target - current
while (df > 180f) df -= 360f
while (df < -180f) df += 360f
return current + df * (1f - exp((-rsp * dt).toDouble()).toFloat())
}
}
object MidnightAssisitClient : ClientModInitializer {
private val t = LoggerFactory.getLogger("MidnightAssist")
private var a: Int = -1
private var b: Boolean = false
private var c: Long = 0L
private val u = 100L
private var d: Int = -1
private val e: MutableMap<Int, Long> = mutableMapOf()
private val v = 5000L
private var f: LockOnCamera? = null
private var g: Int = -1
private var h = false
private var i = 0f
private var j = 0f
private var k = 0f
private var l = 0f
private var m = false
private var n = false
private var o = false
private var p = 0
private var q: Int = -1
private var r: Boolean = false
override fun onInitializeClient() {
t.info("MidnightAssistClient initialized")
UseBlockCallback.EVENT.register { player, _, hand, _ ->
if (shouldCancelItemUse()) ActionResult.FAIL else ActionResult.PASS
}
UseItemCallback.EVENT.register { player, world, hand ->
val sk = player.getStackInHand(hand)
if (shouldCancelItemUse()) TypedActionResult.fail(sk) else TypedActionResult.pass(sk)
}
UseEntityCallback.EVENT.register { player, _, hand, entity, _ ->
if (shouldCancelItemUse()) ActionResult.FAIL else ActionResult.PASS
}
AttackEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
if (o || !MidnightAssisitConfig.data.globalEnabled) return@register ActionResult.PASS
if (entity.id == d || entity.id == a) return@register ActionResult.FAIL
ActionResult.PASS
}
ClientTickEvents.START_CLIENT_TICK.register(ClientTickEvents.StartTick { client: MinecraftClient ->
val player = client.player ?: return@StartTick
val world = client.world ?: return@StartTick
if (!MidnightAssisitConfig.data.globalEnabled) {
resetCameraState()
return@StartTick
}
e.entries.removeAll { System.currentTimeMillis() - it.value > v }
m = false
val er = if (player.abilities.creativeMode) 5.0 else 3.0
if (MidnightAssisitConfig.data.meleeLockOnEnabled) {
if (isUsePressed(client)) {
if (d == -1 && !b) {
val lo = findTarget(client, 5.0, ig = false)
if (lo != null) {
d = lo.id
t.info("Melee lock-on target: {} (id={})", lo.name.string, lo.id)
}
}
if (d != -1) {
val target = world.getEntityById(d)
if (target == null || !target.isAlive || target.distanceTo(player) > er || !player.canSee(target)) {
d = -1
f = null
g = -1
h = false
t.info("Melee lock-on target lost")
}
}
} else if (d != -1) {
t.info("Melee lock-on released")
d = -1
a = -1
f = null
g = -1
b = false
n = false
q = -1
}
}
val mt = if (MidnightAssisitConfig.data.meleeLockOnEnabled && d != -1)
world.getEntityById(d) else null
var ct: Entity? = null
if (mt != null && mt.isAlive) {
ct = mt
a = mt.id
b = true
} else if (a != -1) {
val entity = world.getEntityById(a)
if (entity != null && entity.isAlive && entity.distanceTo(player) <= er && player.canSee(entity)) {
ct = entity
} else {
a = -1
b = false
n = false
}
}
if (q != -1 && ct == null) {
val target = world.getEntityById(q)
if (target != null && target.isAlive && target.distanceTo(player) <= 5.0 && player.canSee(target)) {
ct = target
a = target.id
b = true
c = System.currentTimeMillis()
p = 0
t.info("Acquired intercepted target: {} (id={})", target.name.string, target.id)
} else {
q = -1
n = false
}
}
val rp = isAttackPressed(client)
if (rp && !r) {
n = true
}
r = rp
val ra = rp || n
if (ct == null && ra) {
val crosshair = client.crosshairTarget
if (crosshair != null && crosshair.type == net.minecraft.util.hit.HitResult.Type.ENTITY) {
val hi = (crosshair as EntityHitResult).entity
if (hi != null && hi.isAlive && MidnightAssisitConfig.isEntityEnabled(hi) && hi !is PlayerEntity) {
val nt = hi.id
val nx = a != nt
ct = hi
a = nt
b = true
c = System.currentTimeMillis()
if (nx) p = 0
t.info("Direct target from crosshair: {} (id={})", hi.name.string, hi.id)
} else {
t.info("Crosshair entity rejected, falling back to findTarget")
}
}
if (ct == null) {
ct = findTarget(client, 5.0)
if (ct != null) {
val nt = ct.id
val nx = a != nt
a = nt
b = true
c = System.currentTimeMillis()
if (nx) p = 0
t.info("Found target via findTarget: {} (id={})", ct.name.string, ct.id)
}
}
}
if (ct == null && b) {
b = false
a = -1
f = null
g = -1
n = false
p = 0
}
if (ct == null && n) {
n = false
}
if (a == -1 && d == -1) {
h = false
}
if (MidnightAssisitConfig.data.meleeLockOnEnabled && d != -1) {
val target = world.getEntityById(d)
if (target != null && target.isAlive && target.distanceTo(player) <= er && player.canSee(target)) {
lockedCameraSoft(player, target)
m = true
h = true
} else {
d = -1
f = null
g = -1
h = false
}
}
if (!m && ct != null) {
lockedCameraSoft(player, ct)
h = true
}
if (b) {
client.options.attackKey.setPressed(false)
p++
}
})
ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client: MinecraftClient ->
val player = client.player ?: return@EndTick
val interactionManager = client.interactionManager ?: return@EndTick
val er = if (player.abilities.creativeMode) 5.0 else 3.0
if (b && a != -1 && n && !interactionManager.isBreakingBlock) {
val target = client.world?.getEntityById(a)
if (target != null && target.isAlive && target.distanceTo(player) <= er) {
val ot = isTargetUnderCrosshair(client, target)
val ca = isCameraAligned(player, target)
val cd = player.getAttackCooldownProgress(0.0f)
if (c == 0L) c = System.currentTimeMillis()
val el = System.currentTimeMillis() - c
val fa = p <= 3
val ma = 20
val ck = ot && ca && cd >= 1.0f && 
(if (fa) p >= 3 else el > u)
val sr = p > ma && !ca
if (ck) {
t.info("ATTACK! target={} ot={} ca={} cd={} el={} ticks={}", target.name.string, ot, ca, cd, el, p)
client.getNetworkHandler()?.sendPacket(net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(k, l, player.isOnGround()))
o = true
interactionManager.attackEntity(player, target)
player.swingHand(Hand.MAIN_HAND)
o = false
e[target.id] = System.currentTimeMillis()
c = System.currentTimeMillis()
n = false
val sa = isAttackPressed(client) || n
if (!sa) {
b = false
a = -1
f = null
g = -1
h = false
n = false
q = -1
p = 0
}
} else if (sr) {
t.info("Releasing target - couldn't align cma after {} ticks", ma)
b = false
a = -1
f = null
g = -1
h = false
n = false
q = -1
p = 0
} else {
if (el % 200L < 50L) t.info("Waiting: ot={} ca={} cd={} el={} ticks={}", ot, ca, cd, el, p)
}
} else {
t.info("Target lost: id={}", a)
b = false
a = -1
f = null
g = -1
n = false
q = -1
p = 0
}
}
})
ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client: MinecraftClient ->
val player = client.player ?: return@EndTick
val interactionManager = client.interactionManager ?: return@EndTick
if (!MidnightAssisitConfig.data.globalEnabled) return@EndTick
if (b || a != -1 || d != -1) return@EndTick
val rp = isAttackPressed(client)
if (!rp) return@EndTick
val crosshair = client.crosshairTarget
if (crosshair != null && crosshair.type == net.minecraft.util.hit.HitResult.Type.ENTITY) {
val hi = (crosshair as EntityHitResult).entity
if (hi != null && hi.isAlive && hi !is PlayerEntity) {
val cd = player.getAttackCooldownProgress(0.0f)
if (cd >= 1.0f && !interactionManager.isBreakingBlock) {
interactionManager.attackEntity(player, hi)
player.swingHand(Hand.MAIN_HAND)
}
}
}
})
}
private fun isUsePressed(client: MinecraftClient): Boolean {
if (client.currentScreen != null) return false
return client.options.useKey.isPressed()
}
private fun isAttackPressed(client: MinecraftClient): Boolean {
return try {
val method = KeyBinding::class.java.getMethod("updatePressedState")
method.invoke(client.options.attackKey)
client.options.attackKey.isPressed
} catch (_: Exception) {
client.options.attackKey.isPressed
}
}
private fun isTargetUnderCrosshair(client: MinecraftClient, target: Entity): Boolean {
val player = client.player ?: return false
val rh = 5.0
val cp = player.getCameraPosVec(1.0f)
val rt = player.getRotationVec(1.0f)
val ep = cp.add(rt.x * rh, rt.y * rh, rt.z * rh)
val bx = target.boundingBox.expand(target.targetingMargin.toDouble() + 0.1)
val hit = bx.raycast(cp, ep)
return hit.isPresent
}
private fun isCameraAligned(player: PlayerEntity, target: Entity): Boolean {
val pp = player.getCameraPosVec(1.0f)
val tp = target.boundingBox.center
val dd = tp.subtract(pp).normalize()
val pq = player.getRotationVec(1.0f)
val dp = pq.dotProduct(dd)
return dp > 0.98
}
private fun findTarget(client: MinecraftClient, rg: Double, ig: Boolean = false): Entity? {
val player = client.player ?: return null
val world = client.world ?: return null
val es = world.getOtherEntities(player, player.boundingBox.expand(rg))
val pr = MidnightAssisitConfig.data.targetPriority
val ac = MidnightAssisitConfig.data.aimAccuracy
val nw = System.currentTimeMillis()
var bt: Entity? = null
var bs = Double.MAX_VALUE
for (entity in es) {
if (entity is PlayerEntity || !entity.isAlive) continue
if (!MidnightAssisitConfig.isEntityEnabled(entity)) continue
val dist = entity.distanceTo(player).toDouble()
if (dist > rg) continue
if (!player.canSee(entity)) continue
if (!ig && !isLookingAt(player, entity, ac)) continue
val score = when (pr) {
MidnightAssisitConfig.TargetPriority.NEAREST -> dist
MidnightAssisitConfig.TargetPriority.FARTHEST -> -dist
MidnightAssisitConfig.TargetPriority.WEAKEST -> {
if (entity is LivingEntity) entity.health.toDouble() + dist * 0.01 else dist
}
MidnightAssisitConfig.TargetPriority.STRONGEST -> {
if (entity is LivingEntity) -(entity.health.toDouble()) + dist * 0.01 else -dist
}
MidnightAssisitConfig.TargetPriority.LOOKING_AT_YOU -> {
val isLooking = if (entity is MobEntity) entity.target == player else false
if (isLooking) dist * 0.5 else dist * 2.0
}
MidnightAssisitConfig.TargetPriority.RECENTLY_ATTACKED -> {
val la = e[entity.id]
if (la != null) {
val el = nw - la
if (el < v) dist * 0.3 else dist * 2.0
} else dist * 2.0
}
}
if (score < bs) {
bs = score
bt = entity
}
}
return bt
}
private fun isLookingAt(player: PlayerEntity, entity: Entity, ac: Double): Boolean {
val pp = player.getCameraPosVec(1.0f)
val tp = entity.boundingBox.center
val dd = tp.subtract(pp).normalize()
val pq = player.getRotationVec(1.0f)
val th = 1.0 - ac
return pq.dotProduct(dd) > th
}
private fun lockedCameraSoft(player: PlayerEntity, target: Entity) {
val pp = Vec3(player.x.toFloat(), player.eyeY.toFloat(), player.z.toFloat())
val bb = target.boundingBox.center
val tp = Vec3(bb.x.toFloat(), bb.y.toFloat(), bb.z.toFloat())
val vl = target.velocity
val tv = Vec3(vl.x.toFloat() * 20f, vl.y.toFloat() * 20f, vl.z.toFloat() * 20f)
val aimSpeed = MidnightAssisitConfig.data.aimSpeed.toFloat()
if (f == null || target.id != g || !h) {
val pd = LockOnCamera.predictTarget(tp, tv)
f = LockOnCamera(pd, player.yaw, player.pitch, aimSpeed)
g = target.id
if (!h) {
i = player.yaw
j = player.pitch
k = player.yaw
l = player.pitch
h = true
}
}
val cam = f ?: return
cam.update(pp, tp, tv, 0.05f)
i = k
j = l
k = cam.yw
l = cam.pc
}
private fun resetCameraState() {
a = -1
b = false
d = -1
h = false
g = -1
f = null
n = false
q = -1
r = false
}
@JvmStatic
fun tryInterceptAttack(client: MinecraftClient): Boolean {
if (!MidnightAssisitConfig.data.globalEnabled) return false
val player = client.player ?: return false
val world = client.world ?: return false
if (o) return false
if (d != -1) {
val target = world.getEntityById(d)
if (target != null && target.isAlive) {
a = target.id
b = true
n = true
d = -1
f = null
g = -1
h = false
c = System.currentTimeMillis()
p = 0
t.info("Attack during melee lock-on, hunting: {} (id={})", target.name.string, target.id)
return true
}
}
if (b) return true
val target = findTarget(client, 5.0) ?: return false
a = target.id
b = true
n = true
c = System.currentTimeMillis()
p = 0
t.info("Intercepted attack, hunting: {} (id={})", target.name.string, target.id)
return true
}
@JvmStatic
fun shouldCancelItemUse(): Boolean {
val ins = MinecraftClient.getInstance()
if (ins.player == null || ins.currentScreen != null) return false
return ins.options.useKey.isPressed() && d != -1 && MidnightAssisitConfig.data.meleeLockOnEnabled && MidnightAssisitConfig.data.globalEnabled
}
@JvmStatic
fun applyPartialTicks(pt: Float) {
val player = MinecraftClient.getInstance().player ?: return
if (!h || !MidnightAssisitConfig.data.globalEnabled) return
val yd = MathHelper.wrapDegrees(k - i)
player.setYaw(i + yd * pt)
player.setPitch(j + (l - j) * pt)
}
}
