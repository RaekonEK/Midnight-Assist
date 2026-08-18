package com.one_studio

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.jvm.JvmStatic
import org.slf4j.LoggerFactory

import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback

object MidnightAssistClient : ClientModInitializer {
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
private var s: Int = 0
private val w = 5

override fun onInitializeClient() {
t.info("MidnightAssistClient initialized")

UseBlockCallback.EVENT.register { _, _, _, _ ->
if (shouldCancelItemUse()) InteractionResult.FAIL else InteractionResult.PASS
}
UseItemCallback.EVENT.register { _, _, _ ->
if (shouldCancelItemUse()) InteractionResult.FAIL else InteractionResult.PASS
}
UseEntityCallback.EVENT.register { _, _, _, _, _ ->
if (shouldCancelItemUse()) InteractionResult.FAIL else InteractionResult.PASS
}

AttackEntityCallback.EVENT.register { _, _, _, en, _ ->
if (o || !MidnightAssistConfig.data.globalEnabled) return@register InteractionResult.PASS
if (en.id == d || en.id == a) return@register InteractionResult.FAIL
InteractionResult.PASS
}

ClientTickEvents.START_CLIENT_TICK.register(ClientTickEvents.StartTick { cl: Minecraft ->
val pl = cl.player ?: return@StartTick
val lv = cl.level ?: return@StartTick

if (!MidnightAssistConfig.data.globalEnabled) {
resetCameraState()
return@StartTick
}

e.entries.removeAll { System.currentTimeMillis() - it.value > v }

m = false

val er = if (pl.abilities.instabuild) 5.0 else 3.0

if (MidnightAssistConfig.data.meleeLockOnEnabled) {
if (isUsePressed(cl)) {
s = 0
if (d == -1 && !b) {
val lo = findTarget(cl, 5.0, ignoreFov = false)
if (lo != null) {
d = lo.id
t.info("Melee lock-on tg: {} (id={})", lo.name.string, lo.id)
}
}
if (d != -1) {
val tg = lv.getEntity(d)
if (tg == null || !tg.isAlive || tg.distanceTo(pl) > er || !pl.hasLineOfSight(tg)) {
d = -1
f = null
g = -1
h = false
t.info("Melee lock-on tg lost")
}
}
} else if (d != -1) {
s++
if (s >= w) {
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

val mt = if (MidnightAssistConfig.data.meleeLockOnEnabled && d != -1)
lv.getEntity(d) else null

var ct: Entity? = null

if (mt != null && mt.isAlive) {
ct = mt
a = mt.id
b = true
} else if (a != -1) {
val en = lv.getEntity(a)
if (en != null && en.isAlive && en.distanceTo(pl) <= er && pl.hasLineOfSight(en)) {
ct = en
} else {
a = -1
b = false
n = false
}
}

if (q != -1 && ct == null) {
val tg = lv.getEntity(q)
if (tg != null && tg.isAlive && tg.distanceTo(pl) <= 5.0 && pl.hasLineOfSight(tg)) {
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

val ra = rp || n

if (ct == null && ra) {
val cx = cl.hitResult
var he = false
if (cx != null && cx.type == HitResult.Type.ENTITY) {
val hi = (cx as EntityHitResult).entity
if (hi != null && hi.isAlive && MidnightAssistConfig.isEntityEnabled(hi) && hi !is Player) {
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

if (MidnightAssistConfig.data.meleeLockOnEnabled && d != -1) {
val tg = lv.getEntity(d)
if (tg != null && tg.isAlive && tg.distanceTo(pl) <= er && pl.hasLineOfSight(tg)) {
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
cl.options.keyAttack.isDown = false
p++
}
})

ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { cl: Minecraft ->
val pl = cl.player ?: return@EndTick
val gm = cl.gameMode ?: return@EndTick

val er = if (pl.abilities.instabuild) 5.0 else 3.0

if (b && a != -1 && n && !gm.isDestroying()) {
val tg = cl.level?.getEntity(a)
if (tg != null && tg.isAlive && tg.distanceTo(pl) <= er) {
val ot = isTargetUnderCrosshair(cl, tg)
val ca = isCameraAligned(pl, tg)
val cd = pl.getAttackStrengthScale(0.0f)
if (c == 0L) c = System.currentTimeMillis()
val el = System.currentTimeMillis() - c

val fa = p <= 3
val ma = 20
val ck = ot && ca && cd >= 1.0f &&
(if (fa) p >= 3 else el > u)

val sr = p > ma && !ca

if (ck) {
t.info("ATTACK! tg={} ot={} ca={} cd={} el={} ticks={}", tg.name.string, ot, ca, cd, el, p)

o = true
gm.attack(pl, tg)
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

ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { cl: Minecraft ->
val pl = cl.player ?: return@EndTick
val gm = cl.gameMode ?: return@EndTick

if (!MidnightAssistConfig.data.globalEnabled) return@EndTick
if (b || a != -1 || d != -1) return@EndTick

val rp = isAttackPressed(cl)
if (!rp) return@EndTick

val cx = cl.hitResult
if (cx != null && cx.type == HitResult.Type.ENTITY) {
val hi = (cx as EntityHitResult).entity
if (hi != null && hi.isAlive && hi !is Player) {
val cd = pl.getAttackStrengthScale(0.0f)
if (cd >= 1.0f && !gm.isDestroying()) {
gm.attack(pl, hi)
pl.swing(InteractionHand.MAIN_HAND)
}
}
}
})
}
private data class Vec3f(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
fun add(o: Vec3f) = Vec3f(x + o.x, y + o.y, z + o.z)
fun sub(o: Vec3f) = Vec3f(x - o.x, y - o.y, z - o.z)
fun mul(s: Float) = Vec3f(x * s, y * s, z * s)
}

private class CameraSpring(st: Vec3f, asp: Float = 0.5f) {
var ps = st
private set
private var vc = Vec3f()
private val stf = asp * 400f + 1f
private val dmp = 2f * sqrt(stf) * 0.7f

fun update(tg: Vec3f, dt: Float) {
val tt = tg.sub(ps)
val ax = tt.mul(stf).sub(vc.mul(dmp))
vc = vc.add(ax.mul(dt))
ps = ps.add(vc.mul(dt))
}
}

private class LockOnCamera(st: Vec3f, sy: Float = 0f, sp: Float = 0f, asp: Float = 0.5f) {
private val cma = CameraSpring(st, asp)
var yw = sy
private set
var pc = sp
private set
private val rsp = (asp * 120f).coerceAtLeast(0.01f)

fun update(pp: Vec3f, tp: Vec3f, tv: Vec3f, dt: Float) {
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
fun predictTarget(tp: Vec3f, tv: Vec3f) = tp.add(tv.mul(pdt))
}

private fun dampAngle(cu: Float, tg: Float, dt: Float): Float {
var df = tg - cu
while (df > 180f) df -= 360f
while (df < -180f) df += 360f
return cu + df * (1f - exp((-rsp * dt).toDouble()).toFloat())
}
}

private fun isUsePressed(cl: Minecraft): Boolean {
if (cl.gui.screen() != null) return false
return cl.options.keyUse.isDown
}

private fun isAttackPressed(cl: Minecraft): Boolean {
return cl.options.keyAttack.isDown
}

private fun isTargetUnderCrosshair(cl: Minecraft, tg: Entity): Boolean {
val pl = cl.player ?: return false
val reach = 5.0
val cp = pl.getEyePosition(1.0f)
val rt = pl.lookAngle
val ep = cp.add(rt.x * reach, rt.y * reach, rt.z * reach)
val bx = tg.boundingBox.inflate(tg.pickRadius.toDouble() + 0.1)
val ht = bx.clip(cp, ep)
return ht.isPresent
}

private fun isCameraAligned(pl: Player, tg: Entity): Boolean {
val pp = pl.getEyePosition(1.0f)
val tp = tg.boundingBox.center
val dd = tp.subtract(pp).normalize()
val pq = pl.lookAngle
val dp = pq.dot(dd)
return dp > 0.98
}

private fun findTarget(cl: Minecraft, range: Double, ignoreFov: Boolean = false): Entity? {
val pl = cl.player ?: return null
val lv = cl.level ?: return null
val es = lv.getEntities(pl, pl.boundingBox.inflate(range))

val pr = MidnightAssistConfig.data.targetPriority
val ac = MidnightAssistConfig.data.aimAccuracy
val nw = System.currentTimeMillis()

var bt: Entity? = null
var bs = Double.MAX_VALUE

for (en in es) {
if (en is Player || !en.isAlive) continue
if (!MidnightAssistConfig.isEntityEnabled(en)) continue
val ds = en.distanceTo(pl).toDouble()
if (ds > range) continue
if (!pl.hasLineOfSight(en)) continue
if (!ignoreFov && !isLookingAt(pl, en, ac)) continue

val sc = when (pr) {
MidnightAssistConfig.TargetPriority.NEAREST -> ds
MidnightAssistConfig.TargetPriority.FARTHEST -> -ds
MidnightAssistConfig.TargetPriority.WEAKEST -> {
if (en is LivingEntity) en.health.toDouble() + ds * 0.01 else ds
}
MidnightAssistConfig.TargetPriority.STRONGEST -> {
if (en is LivingEntity) -(en.health.toDouble()) + ds * 0.01 else -ds
}
MidnightAssistConfig.TargetPriority.LOOKING_AT_YOU -> {
val il = if (en is Mob) en.target == pl else false
if (il) ds * 0.5 else ds * 2.0
}
MidnightAssistConfig.TargetPriority.RECENTLY_ATTACKED -> {
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

private fun isLookingAt(pl: Player, en: Entity, ac: Double): Boolean {
val pp = pl.getEyePosition(1.0f)
val tp = en.boundingBox.center
val dd = tp.subtract(pp).normalize()
val pq = pl.lookAngle
val th = 1.0 - ac
return pq.dot(dd) > th
}

private fun lockedCameraSoft(pl: Player, tg: Entity) {
val pp = Vec3f(pl.x.toFloat(), pl.eyeY.toFloat(), pl.z.toFloat())
val bb = tg.boundingBox.center
val tp = Vec3f(bb.x.toFloat(), bb.y.toFloat(), bb.z.toFloat())
val vl = tg.deltaMovement
val tv = Vec3f(vl.x.toFloat() * 20f, vl.y.toFloat() * 20f, vl.z.toFloat() * 20f)
val asp = MidnightAssistConfig.data.aimSpeed.toFloat()
if (f == null || tg.id != g || !h) {
val pd = LockOnCamera.predictTarget(tp, tv)
f = LockOnCamera(pd, pl.yRot, pl.xRot, asp)
g = tg.id
if (!h) {
i = pl.yRot
j = pl.xRot
k = pl.yRot
l = pl.xRot
h = true
}
}
val cm = f ?: return
cm.update(pp, tp, tv, 0.05f)
i = pl.yRot
j = pl.xRot
k = cm.yw
l = cm.pc
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
s = 0
}

@JvmStatic
fun tryInterceptAttack(cl: Minecraft): Boolean {
if (!MidnightAssistConfig.data.globalEnabled) return false
val pl = cl.player ?: return false
val lv = cl.level ?: return false
if (o) return false
if (d != -1) {
val tg = lv.getEntity(d)
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
if (cl.hitResult is EntityHitResult) return false
val tg = findTarget(cl, 5.0) ?: return false
a = tg.id
b = true
n = true
c = System.currentTimeMillis()
p = 0
t.info("Intercepted attack, hunting: {} (id={})", tg.name.string, tg.id)
return true
}

@JvmStatic
fun shouldCancelItemUse(): Boolean {
val ins = Minecraft.getInstance()
val pl = ins.player ?: return false
if (ins.gui.screen() != null) return false
val mh = pl.mainHandItem
val oh = pl.offhandItem
if (mh.`is`(Items.SHIELD) || oh.`is`(Items.SHIELD)) return false
return ins.options.keyUse.isDown && d != -1 && MidnightAssistConfig.data.meleeLockOnEnabled && MidnightAssistConfig.data.globalEnabled
}

@JvmStatic
fun applyPartialTicks(pt: Float) {
val pl = Minecraft.getInstance().player ?: return
if (!h || !MidnightAssistConfig.data.globalEnabled) return
val yd = Mth.wrapDegrees(k - i)
pl.setYRot(i + yd * pt)
pl.setXRot(j + (l - j) * pt)
}
}

