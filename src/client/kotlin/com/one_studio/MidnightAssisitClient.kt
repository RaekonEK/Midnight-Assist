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

private class CameraSpring(st: Vec3, asp: Float = 0.5f) {
var ps = st
private set
private var vc = Vec3()
private val stf = asp * 400f + 1f
private val dmp = 2f * sqrt(stf) * 0.7f

fun update(tg: Vec3, dt: Float) {
val tt = tg.sub(ps)
val ax = tt.mul(stf).sub(vc.mul(dmp))
vc = vc.add(ax.mul(dt))
ps = ps.add(vc.mul(dt))
}
}

private class LockOnCamera(st: Vec3, sy: Float = 0f, sp: Float = 0f, asp: Float = 0.5f) {
private val cma = CameraSpring(st, asp)
var yw = sy
private set
var pc = sp
private set
private val rsp = (asp * 120f).coerceAtLeast(0.01f)

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
private const val pdt = 0.15f
fun predictTarget(tp: Vec3, tv: Vec3) = tp.add(tv.mul(pdt))
}

private fun dampAngle(cu: Float, tg: Float, dt: Float): Float {
var df = tg - cu
while (df > 180f) df -= 360f
while (df < -180f) df += 360f
return cu + df * (1f - exp((-rsp * dt).toDouble()).toFloat())
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
private var meleeUseReleaseTicks: Int = 0
private val MELEE_LOCKON_GRACE_TICKS = 5

override fun onInitializeClient() {
t.info("MidnightAssistClient initialized")

UseBlockCallback.EVENT.register { pl, _, hand, _ ->
if (shouldCancelItemUse()) ActionResult.FAIL else ActionResult.PASS
}
UseItemCallback.EVENT.register { pl, _, hand ->
if (shouldCancelItemUse()) ActionResult.FAIL else ActionResult.PASS
}
UseEntityCallback.EVENT.register { pl, _, hand, en, _ ->
if (shouldCancelItemUse()) ActionResult.FAIL else ActionResult.PASS
}

AttackEntityCallback.EVENT.register { pl, w, hand, en, hitResult ->
if (o || !MidnightAssisitConfig.data.globalEnabled) return@register ActionResult.PASS
if (en.id == d || en.id == a) return@register ActionResult.FAIL
ActionResult.PASS
}

ClientTickEvents.START_CLIENT_TICK.register(ClientTickEvents.StartTick { cl: MinecraftClient ->
val pl = cl.player ?: return@StartTick
val w = cl.world ?: return@StartTick

if (!MidnightAssisitConfig.data.globalEnabled) {
resetCameraState()
return@StartTick
}

e.entries.removeAll { System.currentTimeMillis() - it.value > v }

m = false

val er = if (pl.abilities.creativeMode) 5.0 else 3.0

if (MidnightAssisitConfig.data.meleeLockOnEnabled) {
if (isUsePressed(cl)) {
meleeUseReleaseTicks = 0
if (d == -1 && !b) {
val lo = findTarget(cl, 5.0, ignoreFov = false)
if (lo != null) {
d = lo.id
t.info("Melee lock-on tg: {} (id={})", lo.name.string, lo.id)
}
}
if (d != -1) {
val tg = w.getEntityById(d)
if (tg == null || !tg.isAlive || tg.distanceTo(pl) > er || !pl.canSee(tg)) {
d = -1
f = null
g = -1
h = false
t.info("Melee lock-on tg lost")
}
}
} else if (d != -1) {
meleeUseReleaseTicks++
if (meleeUseReleaseTicks >= MELEE_LOCKON_GRACE_TICKS) {
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
}

val mt = if (MidnightAssisitConfig.data.meleeLockOnEnabled && d != -1)
w.getEntityById(d) else null

var ct: Entity? = null

if (mt != null && mt.isAlive) {
ct = mt
a = mt.id
b = true
} else if (a != -1) {
val en = w.getEntityById(a)
if (en != null && en.isAlive && en.distanceTo(pl) <= er && pl.canSee(en)) {
ct = en
} else {
a = -1
b = false
n = false
}
}

if (q != -1 && ct == null) {
val tg = w.getEntityById(q)
if (tg != null && tg.isAlive && tg.distanceTo(pl) <= 5.0 && pl.canSee(tg)) {
ct = tg
a = tg.id
b = true
c = System.currentTimeMillis()
p = 0
t.info("Acquired intercepted tg: {} (id={})", tg.name.string, tg.id)
} else {
q = -1
n = false
}
}

val rp = isAttackPressed(cl)
if (rp && !r) {
n = true
}
r = rp

val realAttackPressed = rp || n

if (ct == null && realAttackPressed) {
val cx = cl.crosshairTarget
var he = false
if (cx != null && cx.type == net.minecraft.util.hit.HitResult.Type.ENTITY) {
val hi = (cx as EntityHitResult).entity
if (hi != null && hi.isAlive && MidnightAssisitConfig.isEntityEnabled(hi) && hi !is PlayerEntity) {
he = true
}
}
if (!he) {
ct = findTarget(cl, 5.0)
if (ct != null) {
val nt = ct.id
val nx = a != nt
a = nt
b = true
c = System.currentTimeMillis()
if (nx) p = 0
t.info("Found tg via findTarget: {} (id={})", ct.name.string, ct.id)
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
val tg = w.getEntityById(d)
if (tg != null && tg.isAlive && tg.distanceTo(pl) <= er && pl.canSee(tg)) {
lockedCameraSoft(pl, tg)
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
lockedCameraSoft(pl, ct)
h = true
}

if (b) {
cl.options.attackKey.setPressed(false)
p++
}
})

ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { cl: MinecraftClient ->
val pl = cl.player ?: return@EndTick
val im = cl.interactionManager ?: return@EndTick

val er = if (pl.abilities.creativeMode) 5.0 else 3.0

if (b && a != -1 && n && !im.isBreakingBlock) {
val tg = cl.world?.getEntityById(a)
if (tg != null && tg.isAlive && tg.distanceTo(pl) <= er) {
val ot = isTargetUnderCrosshair(cl, tg)
val ca = isCameraAligned(pl, tg)
val cd = pl.getAttackCooldownProgress(0.0f)
if (c == 0L) c = System.currentTimeMillis()
val el = System.currentTimeMillis() - c

val fa = p <= 3
val ma = 20
val ck = ot && ca && cd >= 1.0f &&
(if (fa) p >= 3 else el > u)

val sr = p > ma && !ca

if (ck) {
t.info("ATTACK! tg={} ot={} ca={} cd={} el={} ticks={}", tg.name.string, ot, ca, cd, el, p)

cl.getNetworkHandler()?.sendPacket(net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(k, l, pl.isOnGround(), pl.horizontalCollision))

o = true
im.attackEntity(pl, tg)
o = false
e[tg.id] = System.currentTimeMillis()
c = System.currentTimeMillis()
n = false

val sa = isAttackPressed(cl) || n

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
t.info("Releasing tg - couldn't align cma after {} ticks", ma)
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

ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { cl: MinecraftClient ->
val pl = cl.player ?: return@EndTick
val im = cl.interactionManager ?: return@EndTick

if (!MidnightAssisitConfig.data.globalEnabled) return@EndTick
if (b || a != -1 || d != -1) return@EndTick

val rp = isAttackPressed(cl)
if (!rp) return@EndTick

val cx = cl.crosshairTarget
if (cx != null && cx.type == net.minecraft.util.hit.HitResult.Type.ENTITY) {
val hi = (cx as EntityHitResult).entity
if (hi != null && hi.isAlive && hi !is PlayerEntity) {
val cd = pl.getAttackCooldownProgress(0.0f)
if (cd >= 1.0f && !im.isBreakingBlock) {
im.attackEntity(pl, hi)
pl.swingHand(Hand.MAIN_HAND)
}
}
}
})
}

@JvmStatic
fun applyPartialTicks(pt: Float) {
val pl = MinecraftClient.getInstance().player ?: return
if (!h || !MidnightAssisitConfig.data.globalEnabled) return
val yd = MathHelper.wrapDegrees(k - i)
pl.setYaw(i + yd * pt)
pl.setPitch(j + (l - j) * pt)
}

@JvmStatic
fun shouldCancelItemUse(): Boolean {
val ins = MinecraftClient.getInstance()
val pl = ins.player ?: return false
if (ins.currentScreen != null) return false
if (!MidnightAssisitConfig.data.meleeLockOnEnabled || !MidnightAssisitConfig.data.globalEnabled) return false
if (d == -1) return false
if (!ins.options.useKey.isPressed()) return false
if (pl.mainHandStack.`isOf`(net.minecraft.item.Items.SHIELD) || pl.offHandStack.`isOf`(net.minecraft.item.Items.SHIELD)) return false
return true
}

@JvmStatic
fun tryInterceptAttack(cl: MinecraftClient): Boolean {
if (!MidnightAssisitConfig.data.globalEnabled) return false
val pl = cl.player ?: return false
val w = cl.world ?: return false
if (o) return false
if (d != -1) {
val tg = w.getEntityById(d)
if (tg != null && tg.isAlive) {
a = tg.id
b = true
n = true
d = -1
f = null
g = -1
h = false
c = System.currentTimeMillis()
p = 0
t.info("Attack during melee lock-on, hunting: {} (id={})", tg.name.string, tg.id)
return true
}
}
if (b) return true
if (cl.crosshairTarget is EntityHitResult) return false
val tg = findTarget(cl, 5.0) ?: return false
a = tg.id
b = true
n = true
c = System.currentTimeMillis()
p = 0
t.info("Intercepted attack, hunting: {} (id={})", tg.name.string, tg.id)
return true
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
meleeUseReleaseTicks = 0
}

private fun lockedCameraSoft(pl: PlayerEntity, tg: Entity) {
val pp = Vec3(pl.x.toFloat(), pl.eyeY.toFloat(), pl.z.toFloat())
val bb = tg.boundingBox.center
val tp = Vec3(bb.x.toFloat(), bb.y.toFloat(), bb.z.toFloat())
val vl = tg.velocity
val tv = Vec3(vl.x.toFloat() * 20f, vl.y.toFloat() * 20f, vl.z.toFloat() * 20f)
val asp = MidnightAssisitConfig.data.aimSpeed.toFloat()
if (f == null || tg.id != g || !h) {
val pd = LockOnCamera.predictTarget(tp, tv)
f = LockOnCamera(pd, pl.yaw, pl.pitch, asp)
g = tg.id
if (!h) {
i = pl.yaw
j = pl.pitch
k = pl.yaw
l = pl.pitch
h = true
}
}
val cm = f ?: return
cm.update(pp, tp, tv, 0.05f)
i = pl.yaw
j = pl.pitch
k = cm.yw
l = cm.pc
}

private fun lockedCamera(pl: PlayerEntity, tg: Entity) {
val pp = Vec3(pl.x.toFloat(), pl.eyeY.toFloat(), pl.z.toFloat())
val bb = tg.boundingBox.center
val tp = Vec3(bb.x.toFloat(), bb.y.toFloat(), bb.z.toFloat())
val vl = tg.velocity
val tv = Vec3(vl.x.toFloat() * 20f, vl.y.toFloat() * 20f, vl.z.toFloat() * 20f)
val asp = MidnightAssisitConfig.data.aimSpeed.toFloat()
if (f == null || tg.id != g) {
val pd = LockOnCamera.predictTarget(tp, tv)
f = LockOnCamera(pd, pl.yaw, pl.pitch, asp)
g = tg.id
i = pl.yaw
j = pl.pitch
k = pl.yaw
l = pl.pitch
}
val cm = f ?: return
cm.update(pp, tp, tv, 0.05f)
i = k
j = l
k = cm.yw
l = cm.pc
pl.setYaw(k)
pl.setPitch(l)
}

private fun isLookingAt(pl: PlayerEntity, en: Entity, ac: Double): Boolean {
val pp = pl.getCameraPosVec(1.0f)
val tp = en.boundingBox.center
val dd = tp.subtract(pp).normalize()
val pq = pl.getRotationVec(1.0f)
val th = 1.0 - ac
return pq.dotProduct(dd) > th
}

private fun findTarget(cl: MinecraftClient, range: Double, ignoreFov: Boolean = false): Entity? {
val pl = cl.player ?: return null
val w = cl.world ?: return null
val es = w.getOtherEntities(pl, pl.boundingBox.expand(range))

val pr = MidnightAssisitConfig.data.targetPriority
val ac = MidnightAssisitConfig.data.aimAccuracy
val nw = System.currentTimeMillis()

var bt: Entity? = null
var bs = Double.MAX_VALUE

for (en in es) {
if (en is PlayerEntity || !en.isAlive) continue
if (!MidnightAssisitConfig.isEntityEnabled(en)) continue
val ds = en.distanceTo(pl).toDouble()
if (ds > range) continue
if (!pl.canSee(en)) continue
if (!ignoreFov && !isLookingAt(pl, en, ac)) continue

val sc = when (pr) {
MidnightAssisitConfig.TargetPriority.NEAREST -> ds
MidnightAssisitConfig.TargetPriority.FARTHEST -> -ds
MidnightAssisitConfig.TargetPriority.WEAKEST -> {
if (en is LivingEntity) en.health.toDouble() + ds * 0.01 else ds
}
MidnightAssisitConfig.TargetPriority.STRONGEST -> {
if (en is LivingEntity) -(en.health.toDouble()) + ds * 0.01 else -ds
}
MidnightAssisitConfig.TargetPriority.LOOKING_AT_YOU -> {
val il = if (en is MobEntity) en.target == pl else false
if (il) ds * 0.5 else ds * 2.0
}
MidnightAssisitConfig.TargetPriority.RECENTLY_ATTACKED -> {
val la = e[en.id]
if (la != null) {
val el = nw - la
if (el < v) ds * 0.3 else ds * 2.0
} else ds * 2.0
}
}

if (sc < bs) {
bs = sc
bt = en
}
}
return bt
}

private fun isCameraAligned(pl: PlayerEntity, tg: Entity): Boolean {
val pp = pl.getCameraPosVec(1.0f)
val tp = tg.boundingBox.center
val dd = tp.subtract(pp).normalize()
val pq = pl.getRotationVec(1.0f)
val dp = pq.dotProduct(dd)
return dp > 0.98
}

private fun isTargetUnderCrosshair(cl: MinecraftClient, tg: Entity): Boolean {
val pl = cl.player ?: return false
val reach = 5.0
val cp = pl.getCameraPosVec(1.0f)
val rt = pl.getRotationVec(1.0f)
val ep = cp.add(rt.x * reach, rt.y * reach, rt.z * reach)
val bx = tg.boundingBox.expand(tg.targetingMargin.toDouble() + 0.1)
val ht = bx.raycast(cp, ep)
return ht.isPresent
}

private fun isAttackPressed(cl: MinecraftClient): Boolean {
return try {
val method = KeyBinding::class.java.getMethod("updatePressedState")
method.invoke(cl.options.attackKey)
cl.options.attackKey.isPressed
} catch (_: Exception) {
cl.options.attackKey.isPressed
}
}

private fun isUsePressed(cl: MinecraftClient): Boolean {
if (cl.currentScreen != null) return false
return cl.options.useKey.isPressed()
}
}
