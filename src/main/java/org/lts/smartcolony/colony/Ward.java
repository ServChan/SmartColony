package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lts.smartcolony.core.Blueprint;
import org.lts.smartcolony.core.Blueprints;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.CraftPlanner;
import org.lts.smartcolony.core.Hospital;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.mixin.ZombieVillagerInvoker;
import org.lts.smartcolony.util.WorldUtil;
import org.lts.smartcolony.work.Colonist;

public final class Ward {
    private static final int PERIOD = 20;
    private static final long TIMEOUT = 9000;
    private static final long STUCK_AFTER = 1200;
    private static final long COOLDOWN = 6000;
    private static final float SPEED = 0.55F;
    private static final int CONVERSION_TICKS = 3600;

    public static final class Patient {
        private final UUID id;
        private final long since;
        private BlockPos bed;
        private boolean critical;
        private boolean arrived;
        private long rested;
        private long treatedAt = Long.MIN_VALUE / 2;
        private long rescuedAt = Long.MIN_VALUE / 2;

        private Patient(UUID id, long since) {
            this.id = id;
            this.since = since;
        }

        public UUID id() {
            return id;
        }

        public BlockPos bed() {
            return bed;
        }

        public boolean arrived() {
            return arrived;
        }

        public boolean critical() {
            return critical;
        }

        public long treatedAt() {
            return treatedAt;
        }

        public boolean stuck(long now) {
            return !arrived && now - since > STUCK_AFTER;
        }
    }

    private final Colony colony;
    private final Map<UUID, Patient> patients = new LinkedHashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Map<UUID, Long> claims = new HashMap<>();
    private String layoutKey = "";
    private List<BlockPos> cots = List.of();
    private List<BlockPos> mats = List.of();
    private BlockPos post;
    private BlockPos entrance;
    private boolean cureReady;
    private long cureCheckedAt = Long.MIN_VALUE / 2;

    Ward(Colony colony) {
        this.colony = colony;
    }

    private ColonyData data() {
        return colony.data();
    }

    public ColonyData.Building building() {
        ColonyData.Building best = null;
        for (ColonyData.Building building : data().buildings) {
            if (Hospital.stageOf(building.blueprint) > 0
                    && (best == null || Hospital.stageOf(building.blueprint) > Hospital.stageOf(best.blueprint))) {
                best = building;
            }
        }
        return best;
    }

    public int stage() {
        return Hospital.stage(data());
    }

    public int beds() {
        return cots.size() + mats.size();
    }

    public int occupied() {
        return patients.size();
    }

    public BlockPos post() {
        return post == null ? colony.depot() : post;
    }

    public BlockPos entrance() {
        return entrance == null ? colony.depot() : entrance;
    }

    public void finished(ServerLevel level, ColonyData.Building building) {
        int stage = Hospital.stageOf(building.blueprint);
        colony.journal(level, "infirmary finished: stage " + stage + " of " + Hospital.STAGES + " at " + building.origin);
        colony.notify(level, Component.translatable("smartcolony.chat.infirmary_stage", stage, Hospital.STAGES));
        layoutKey = "";
    }

    public boolean medicExists() {
        for (Colonist colonist : colony.colonists().values()) {
            if (colonist.role() == Role.MEDIC) {
                return true;
            }
        }
        return false;
    }

    public boolean spares(Entity entity) {
        if (!(entity instanceof ZombieVillager) || !cureReady) {
            return false;
        }
        return Hospital.sparesZombie(true, entity.blockPosition().distSqr(colony.center()), colony.data().radius + 4);
    }

    public String summary() {
        return stage() + "|" + data().stat("medic_wounded") + "|" + data().stat("medic_healed") + "|" + data().stat("medic_saved")
                + "|" + data().stat("zombies_cured") + "|" + patients.size();
    }

    public Patient patient(UUID uuid) {
        return patients.get(uuid);
    }

    public void tick(ServerLevel level) {
        if (colony.ticks() % PERIOD != 9) {
            return;
        }
        ColonyData.Building building = building();
        if (building == null) {
            if (!patients.isEmpty()) {
                dischargeAll(level);
            }
            cureReady = false;
            noteFirstInjury(level);
            return;
        }
        refreshLayout(building);
        long now = level.getGameTime();
        refreshCure(level, now);
        boolean onShift = colony.workTime(level);
        boolean combat = !colony.defense().enemies().isEmpty();
        double limit = colony.data().radius + 24;
        for (Map.Entry<UUID, Colonist> entry : colony.colonists().entrySet()) {
            UUID uuid = entry.getKey();
            if (!(level.getEntity(uuid) instanceof Villager villager) || !villager.isAlive()) {
                patients.remove(uuid);
                continue;
            }
            Colonist colonist = entry.getValue();
            Patient patient = patients.get(uuid);
            if (patient != null) {
                follow(level, villager, colonist, patient, now);
                continue;
            }
            if (villager.isTrading() || villager.isPassenger() || cooldown.getOrDefault(uuid, 0L) > now) {
                continue;
            }
            if (colonist.role() == Role.GUARD && combat) {
                continue;
            }
            Hospital.Triage triage = Hospital.triage(villager.getHealth(), villager.getMaxHealth(), onShift);
            if (triage == Hospital.Triage.NONE || villager.blockPosition().distSqr(colony.center()) > limit * limit) {
                continue;
            }
            admit(level, villager, colonist, now);
        }
        cooldown.values().removeIf(until -> until < now);
        claims.values().removeIf(until -> until < now);
        if (cureReady) {
            hold(level);
        }
    }

    private void noteFirstInjury(ServerLevel level) {
        if (data().stat("first_injury") > 0) {
            return;
        }
        for (UUID uuid : colony.colonists().keySet()) {
            if (level.getEntity(uuid) instanceof Villager villager && villager.isAlive()
                    && villager.getHealth() < villager.getMaxHealth() * Hospital.ADMIT_AFTER_SHIFT) {
                data().bump("first_injury", 1);
                colony.markDirty();
                return;
            }
        }
    }

    private void admit(ServerLevel level, Villager villager, Colonist colonist, long now) {
        Patient patient = new Patient(villager.getUUID(), now);
        patient.critical = Hospital.critical(villager.getHealth(), villager.getMaxHealth());
        patients.put(villager.getUUID(), patient);
        data().bump("medic_wounded", 1);
        if (villager.isSleeping()) {
            villager.stopSleeping();
        }
        colonist.hold(level, villager, now + 80, "wounded");
        assign(villager, patient);
        if (patient.critical) {
            colony.journal(level, colonist.role().key() + " is badly hurt and is carried to the infirmary");
        }
        colony.markDirty();
    }

    private void follow(ServerLevel level, Villager villager, Colonist colonist, Patient patient, long now) {
        if (Hospital.discharged(villager.getHealth(), villager.getMaxHealth())) {
            discharge(level, villager, colonist, patient, true);
            return;
        }
        if (now - patient.since > TIMEOUT) {
            discharge(level, villager, colonist, patient, false);
            return;
        }
        if (patient.stuck(now) && now - patient.rescuedAt > STUCK_AFTER * 4) {
            cooldown.put(patient.id, now + COOLDOWN);
            patients.remove(patient.id);
            colonist.hold(level, villager, now, "lost");
            return;
        }
        colonist.hold(level, villager, now + 80, "wounded");
        if (patient.bed == null) {
            assign(villager, patient);
        }
        BlockPos goal = patient.bed != null ? patient.bed : entrance();
        double distance = villager.position().distanceTo(Vec3.atBottomCenterOf(goal));
        if (distance > 2.2) {
            patient.arrived = false;
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(goal, SPEED, 1));
            return;
        }
        patient.arrived = patient.bed != null;
        villager.getNavigation().stop();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        Vec3 look = Vec3.atCenterOf(goal);
        villager.getLookControl().setLookAt(look.x, look.y, look.z);
        if (!patient.arrived) {
            return;
        }
        patient.rested += PERIOD;
        float heal = Hospital.restHeal(patient.rested);
        if (heal > 0.0F) {
            villager.heal(heal);
            level.sendParticles(ParticleTypes.HEART, villager.getX(), villager.getY() + 1.8, villager.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
        }
    }

    private void discharge(ServerLevel level, Villager villager, Colonist colonist, Patient patient, boolean healed) {
        patients.remove(patient.id);
        colonist.hold(level, villager, level.getGameTime(), "healed");
        if (healed) {
            data().bump("medic_healed", 1);
            if (patient.critical) {
                data().bump("medic_saved", 1);
                colony.journal(level, colonist.role().key() + " recovered after a near-fatal wound");
            }
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 1.5, villager.getZ(), 4, 0.4, 0.4, 0.4, 0.0);
        } else {
            cooldown.put(patient.id, level.getGameTime() + COOLDOWN);
        }
        colony.markDirty();
    }

    private void dischargeAll(ServerLevel level) {
        for (UUID uuid : new ArrayList<>(patients.keySet())) {
            Colonist colonist = colony.colonists().get(uuid);
            if (colonist != null && level.getEntity(uuid) instanceof Villager villager) {
                colonist.hold(level, villager, level.getGameTime(), "healed");
            }
        }
        patients.clear();
    }

    private void assign(Villager villager, Patient patient) {
        List<BlockPos> pool = new ArrayList<>();
        if (villager.isBaby()) {
            pool.addAll(mats);
            pool.addAll(cots);
        } else {
            pool.addAll(cots);
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos bed : pool) {
            boolean taken = false;
            for (Patient other : patients.values()) {
                if (other != patient && bed.equals(other.bed)) {
                    taken = true;
                    break;
                }
            }
            if (taken) {
                continue;
            }
            double distance = bed.distSqr(villager.blockPosition());
            if (villager.isBaby() && mats.contains(bed)) {
                distance -= 10000;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = bed;
            }
        }
        patient.bed = best;
    }

    private void refreshLayout(ColonyData.Building building) {
        String key = building.blueprint + "@" + building.origin + "r" + building.rotation;
        if (key.equals(layoutKey)) {
            return;
        }
        layoutKey = key;
        Blueprint blueprint = Blueprints.get(building.blueprint);
        List<Blueprint.Cell> cells = blueprint.cells(building.wood);
        Map<String, String> states = new HashMap<>();
        for (Blueprint.Cell cell : cells) {
            states.put(cell.x() + "," + cell.y() + "," + cell.z(), cell.state());
        }
        List<BlockPos> newCots = new ArrayList<>();
        List<BlockPos> newMats = new ArrayList<>();
        BlockPos brewing = null;
        BlockPos barrel = null;
        for (Blueprint.Cell cell : cells) {
            String path = Ids.path(Blueprint.blockId(cell.state()));
            BlockPos at = colony.construction().world(building, cell.x(), cell.y(), cell.z());
            if (path.endsWith("_carpet")) {
                String below = states.get(cell.x() + "," + (cell.y() - 1) + "," + cell.z());
                if (below != null && Ids.path(Blueprint.blockId(below)).endsWith("_slab")) {
                    newCots.add(at);
                } else {
                    newMats.add(at);
                }
            } else if (path.equals("brewing_stand")) {
                brewing = at;
            } else if (path.equals("barrel") && barrel == null) {
                barrel = at;
            }
        }
        cots = newCots;
        mats = newMats;
        post = brewing != null ? brewing : barrel;
        entrance = WorldUtil.bp(building.door);
    }

    private void refreshCure(ServerLevel level, long now) {
        if (now - cureCheckedAt < 200) {
            return;
        }
        cureCheckedAt = now;
        Stock stock = colony.warehouse().stock(level);
        int golden = stock.get(Hospital.GOLDEN);
        boolean craftable = golden == 0 && CraftPlanner.plan(stock, Hospital.GOLDEN, 1).isPresent();
        cureReady = Hospital.cureReady(medicExists(), golden, craftable);
    }

    private AABB area() {
        int radius = colony.data().radius + 4;
        return new AABB(colony.center()).inflate(radius, 24, radius);
    }

    private void hold(ServerLevel level) {
        for (ZombieVillager zombie : level.getEntitiesOfClass(ZombieVillager.class, area(), ZombieVillager::isAlive)) {
            if (!spares(zombie)) {
                continue;
            }
            zombie.setPersistenceRequired();
            zombie.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 120, 3, true, false));
            zombie.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 120, 3, true, false));
            if (zombie.isConverting()) {
                zombie.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 120, 0, true, false));
            }
        }
    }

    public ZombieVillager cureTarget(ServerLevel level, BlockPos from) {
        if (!cureReady) {
            return null;
        }
        ZombieVillager best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ZombieVillager zombie : level.getEntitiesOfClass(ZombieVillager.class, area(), ZombieVillager::isAlive)) {
            if (zombie.isConverting() || !spares(zombie)) {
                continue;
            }
            double distance = zombie.blockPosition().distSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = zombie;
            }
        }
        return best;
    }

    public boolean cure(ServerLevel level, ZombieVillager zombie) {
        if (zombie.isConverting() || !zombie.isAlive()) {
            return false;
        }
        Stock stock = colony.warehouse().stock(level);
        if (stock.get(Hospital.GOLDEN) == 0 && !colony.craft(level, Hospital.GOLDEN, 1)) {
            return false;
        }
        if (colony.warehouse().take(level, Hospital.GOLDEN, 1) < 1) {
            return false;
        }
        zombie.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, CONVERSION_TICKS + 600, 0, true, false));
        ((ZombieVillagerInvoker) zombie).smartcolony$startConverting(null, CONVERSION_TICKS);
        data().bump("zombies_cured", 1);
        colony.journal(level, "a medic began curing a zombie villager at " + zombie.blockPosition());
        colony.markDirty();
        return true;
    }

    public Villager nextPatient(ServerLevel level, long now) {
        Villager best = null;
        double bestRatio = 2.0;
        for (Patient patient : patients.values()) {
            if (now - patient.treatedAt < 600 || !(level.getEntity(patient.id) instanceof Villager villager) || !villager.isAlive()) {
                continue;
            }
            double ratio = villager.getHealth() / villager.getMaxHealth();
            if (ratio >= Hospital.DISCHARGE - 0.05 || ratio >= bestRatio) {
                continue;
            }
            bestRatio = ratio;
            best = villager;
        }
        return best;
    }

    public Villager nextRescue(ServerLevel level, UUID medic, long now) {
        Villager best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos home = post();
        for (UUID uuid : colony.colonists().keySet()) {
            Long claim = claims.get(uuid);
            if (claim != null && claim > now && !medic.equals(claimant(uuid))) {
                continue;
            }
            if (!(level.getEntity(uuid) instanceof Villager villager) || !villager.isAlive() || uuid.equals(medic)) {
                continue;
            }
            Patient patient = patients.get(uuid);
            if (patient != null && (patient.arrived || now - patient.rescuedAt < 1200)) {
                continue;
            }
            double away = Math.sqrt(villager.blockPosition().distSqr(home));
            boolean stuck = patient != null && patient.stuck(now);
            if (!stuck && !Hospital.wantsFieldRescue(villager.getHealth(), villager.getMaxHealth(), away, villager.getAirSupply(),
                    villager.getMaxAirSupply(), villager.isUnderWater())) {
                continue;
            }
            if (away < bestDistance) {
                bestDistance = away;
                best = villager;
            }
        }
        return best;
    }

    private final Map<UUID, UUID> claimants = new HashMap<>();

    private UUID claimant(UUID target) {
        return claimants.get(target);
    }

    public void claim(UUID target, UUID medic, long until) {
        claims.put(target, until);
        claimants.put(target, medic);
    }

    public void escortHome(ServerLevel level, Villager patient) {
        Patient entry = patients.get(patient.getUUID());
        long now = level.getGameTime();
        if (entry != null) {
            entry.rescuedAt = now;
            if (entry.stuck(now) || patient.isUnderWater()) {
                org.lts.smartcolony.work.Mover.rescue(level, patient, entrance().above());
            }
        }
    }

    public boolean treat(ServerLevel level, Villager medic, Villager patient) {
        Patient entry = patients.get(patient.getUUID());
        if (entry != null && level.getGameTime() - entry.treatedAt < 200) {
            return true;
        }
        if (Hospital.critical(patient.getHealth(), patient.getMaxHealth())) {
            net.minecraft.world.item.ItemStack potion = colony.warehouse().extractOne(level, org.lts.smartcolony.util.ItemIds::healingPotion);
            if (!potion.isEmpty()) {
                net.minecraft.world.item.alchemy.PotionContents contents = potion.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
                if (contents != null) {
                    contents.applyToLivingEntity(patient, 1.0F);
                }
                colony.warehouse().insert(level, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE));
                if (entry != null) {
                    entry.treatedAt = level.getGameTime();
                }
                level.sendParticles(ParticleTypes.HEART, patient.getX(), patient.getY() + 1.5, patient.getZ(), 4, 0.4, 0.4, 0.4, 0.0);
                data().bump("medic_potions", 1);
                return true;
            }
        }
        Stock stock = colony.warehouse().stock(level);
        if (stock.get(Hospital.BANDAGE) + stock.get(Hospital.REMEDY) + stock.get(Hospital.GOLDEN) == 0
                && colony.warehouse().take(level, org.lts.smartcolony.core.Ids.mc("honey_bottle"), 1) == 1) {
            patient.heal(patient.getMaxHealth() * 0.25F);
            patient.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 0));
            colony.warehouse().insert(level, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE));
            if (entry != null) {
                entry.treatedAt = level.getGameTime();
            }
            data().bump("medic_honey", 1);
            return true;
        }
        Hospital.Care care = Hospital.chooseCare(patient.getHealth(), patient.getMaxHealth(), stock.get(Hospital.BANDAGE),
                stock.get(Hospital.REMEDY), stock.get(Hospital.GOLDEN));
        if (care == Hospital.Care.NONE) {
            return false;
        }
        if (colony.warehouse().take(level, Hospital.supplyItem(care), 1) < 1) {
            return false;
        }
        patient.heal(Hospital.healAmount(care, patient.getMaxHealth()));
        int ticks = Hospital.regenerationTicks(care);
        if (ticks > 0) {
            patient.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ticks, Hospital.regenerationAmplifier(care)));
        }
        if (entry != null) {
            entry.treatedAt = level.getGameTime();
        }
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, patient.getX(), patient.getY() + 1.5, patient.getZ(), 6, 0.4, 0.4, 0.4, 0.0);
        level.playSound(null, patient.blockPosition(), SoundEvents.BREWING_STAND_BREW, SoundSource.NEUTRAL, 0.6F, 1.4F);
        data().bump(switch (care) {
            case BANDAGE -> "medic_bandages";
            case REMEDY -> "medic_remedies";
            default -> "medic_golden";
        }, 1);
        return true;
    }

    public int supplies(ServerLevel level, String item) {
        return colony.warehouse().stock(level).get(item);
    }
}
