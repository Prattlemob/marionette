package com.prattlemob.marionette.observation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Classifies every vanilla 1.21.8 entity type from its real class (loaded, never
 * initialized) and compares the result with the table published in protocol/v1.md.
 * The spawn category needs a bootstrapped registry, so {@code Enemy} stands in for
 * it here; no vanilla mob is in the monster category without being an {@code Enemy}.
 */
class VanillaHostilityTest {
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("minecraft:acacia_boat", "net.minecraft.world.entity.vehicle.Boat"),
            Map.entry("minecraft:acacia_chest_boat", "net.minecraft.world.entity.vehicle.ChestBoat"),
            Map.entry("minecraft:allay", "net.minecraft.world.entity.animal.allay.Allay"),
            Map.entry("minecraft:area_effect_cloud", "net.minecraft.world.entity.AreaEffectCloud"),
            Map.entry("minecraft:armadillo", "net.minecraft.world.entity.animal.armadillo.Armadillo"),
            Map.entry("minecraft:armor_stand", "net.minecraft.world.entity.decoration.ArmorStand"),
            Map.entry("minecraft:arrow", "net.minecraft.world.entity.projectile.Arrow"),
            Map.entry("minecraft:axolotl", "net.minecraft.world.entity.animal.axolotl.Axolotl"),
            Map.entry("minecraft:bamboo_chest_raft", "net.minecraft.world.entity.vehicle.ChestRaft"),
            Map.entry("minecraft:bamboo_raft", "net.minecraft.world.entity.vehicle.Raft"),
            Map.entry("minecraft:bat", "net.minecraft.world.entity.ambient.Bat"),
            Map.entry("minecraft:bee", "net.minecraft.world.entity.animal.Bee"),
            Map.entry("minecraft:birch_boat", "net.minecraft.world.entity.vehicle.Boat"),
            Map.entry("minecraft:birch_chest_boat", "net.minecraft.world.entity.vehicle.ChestBoat"),
            Map.entry("minecraft:blaze", "net.minecraft.world.entity.monster.Blaze"),
            Map.entry("minecraft:block_display", "net.minecraft.world.entity.Display$BlockDisplay"),
            Map.entry("minecraft:bogged", "net.minecraft.world.entity.monster.Bogged"),
            Map.entry("minecraft:breeze", "net.minecraft.world.entity.monster.breeze.Breeze"),
            Map.entry("minecraft:breeze_wind_charge", "net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge"),
            Map.entry("minecraft:camel", "net.minecraft.world.entity.animal.camel.Camel"),
            Map.entry("minecraft:cat", "net.minecraft.world.entity.animal.Cat"),
            Map.entry("minecraft:cave_spider", "net.minecraft.world.entity.monster.CaveSpider"),
            Map.entry("minecraft:cherry_boat", "net.minecraft.world.entity.vehicle.Boat"),
            Map.entry("minecraft:cherry_chest_boat", "net.minecraft.world.entity.vehicle.ChestBoat"),
            Map.entry("minecraft:chest_minecart", "net.minecraft.world.entity.vehicle.MinecartChest"),
            Map.entry("minecraft:chicken", "net.minecraft.world.entity.animal.Chicken"),
            Map.entry("minecraft:cod", "net.minecraft.world.entity.animal.Cod"),
            Map.entry("minecraft:command_block_minecart", "net.minecraft.world.entity.vehicle.MinecartCommandBlock"),
            Map.entry("minecraft:cow", "net.minecraft.world.entity.animal.Cow"),
            Map.entry("minecraft:creaking", "net.minecraft.world.entity.monster.creaking.Creaking"),
            Map.entry("minecraft:creeper", "net.minecraft.world.entity.monster.Creeper"),
            Map.entry("minecraft:dark_oak_boat", "net.minecraft.world.entity.vehicle.Boat"),
            Map.entry("minecraft:dark_oak_chest_boat", "net.minecraft.world.entity.vehicle.ChestBoat"),
            Map.entry("minecraft:dolphin", "net.minecraft.world.entity.animal.Dolphin"),
            Map.entry("minecraft:donkey", "net.minecraft.world.entity.animal.horse.Donkey"),
            Map.entry("minecraft:dragon_fireball", "net.minecraft.world.entity.projectile.DragonFireball"),
            Map.entry("minecraft:drowned", "net.minecraft.world.entity.monster.Drowned"),
            Map.entry("minecraft:egg", "net.minecraft.world.entity.projectile.ThrownEgg"),
            Map.entry("minecraft:elder_guardian", "net.minecraft.world.entity.monster.ElderGuardian"),
            Map.entry("minecraft:enderman", "net.minecraft.world.entity.monster.EnderMan"),
            Map.entry("minecraft:endermite", "net.minecraft.world.entity.monster.Endermite"),
            Map.entry("minecraft:ender_dragon", "net.minecraft.world.entity.boss.enderdragon.EnderDragon"),
            Map.entry("minecraft:ender_pearl", "net.minecraft.world.entity.projectile.ThrownEnderpearl"),
            Map.entry("minecraft:end_crystal", "net.minecraft.world.entity.boss.enderdragon.EndCrystal"),
            Map.entry("minecraft:evoker", "net.minecraft.world.entity.monster.Evoker"),
            Map.entry("minecraft:evoker_fangs", "net.minecraft.world.entity.projectile.EvokerFangs"),
            Map.entry("minecraft:experience_bottle", "net.minecraft.world.entity.projectile.ThrownExperienceBottle"),
            Map.entry("minecraft:experience_orb", "net.minecraft.world.entity.ExperienceOrb"),
            Map.entry("minecraft:eye_of_ender", "net.minecraft.world.entity.projectile.EyeOfEnder"),
            Map.entry("minecraft:falling_block", "net.minecraft.world.entity.item.FallingBlockEntity"),
            Map.entry("minecraft:fireball", "net.minecraft.world.entity.projectile.LargeFireball"),
            Map.entry("minecraft:firework_rocket", "net.minecraft.world.entity.projectile.FireworkRocketEntity"),
            Map.entry("minecraft:fox", "net.minecraft.world.entity.animal.Fox"),
            Map.entry("minecraft:frog", "net.minecraft.world.entity.animal.frog.Frog"),
            Map.entry("minecraft:furnace_minecart", "net.minecraft.world.entity.vehicle.MinecartFurnace"),
            Map.entry("minecraft:ghast", "net.minecraft.world.entity.monster.Ghast"),
            Map.entry("minecraft:happy_ghast", "net.minecraft.world.entity.animal.HappyGhast"),
            Map.entry("minecraft:giant", "net.minecraft.world.entity.monster.Giant"),
            Map.entry("minecraft:glow_item_frame", "net.minecraft.world.entity.decoration.GlowItemFrame"),
            Map.entry("minecraft:glow_squid", "net.minecraft.world.entity.GlowSquid"),
            Map.entry("minecraft:goat", "net.minecraft.world.entity.animal.goat.Goat"),
            Map.entry("minecraft:guardian", "net.minecraft.world.entity.monster.Guardian"),
            Map.entry("minecraft:hoglin", "net.minecraft.world.entity.monster.hoglin.Hoglin"),
            Map.entry("minecraft:hopper_minecart", "net.minecraft.world.entity.vehicle.MinecartHopper"),
            Map.entry("minecraft:horse", "net.minecraft.world.entity.animal.horse.Horse"),
            Map.entry("minecraft:husk", "net.minecraft.world.entity.monster.Husk"),
            Map.entry("minecraft:illusioner", "net.minecraft.world.entity.monster.Illusioner"),
            Map.entry("minecraft:interaction", "net.minecraft.world.entity.Interaction"),
            Map.entry("minecraft:iron_golem", "net.minecraft.world.entity.animal.IronGolem"),
            Map.entry("minecraft:item", "net.minecraft.world.entity.item.ItemEntity"),
            Map.entry("minecraft:item_display", "net.minecraft.world.entity.Display$ItemDisplay"),
            Map.entry("minecraft:item_frame", "net.minecraft.world.entity.decoration.ItemFrame"),
            Map.entry("minecraft:jungle_boat", "net.minecraft.world.entity.vehicle.Boat"),
            Map.entry("minecraft:jungle_chest_boat", "net.minecraft.world.entity.vehicle.ChestBoat"),
            Map.entry("minecraft:leash_knot", "net.minecraft.world.entity.decoration.LeashFenceKnotEntity"),
            Map.entry("minecraft:lightning_bolt", "net.minecraft.world.entity.LightningBolt"),
            Map.entry("minecraft:llama", "net.minecraft.world.entity.animal.horse.Llama"),
            Map.entry("minecraft:llama_spit", "net.minecraft.world.entity.projectile.LlamaSpit"),
            Map.entry("minecraft:magma_cube", "net.minecraft.world.entity.monster.MagmaCube"),
            Map.entry("minecraft:mangrove_boat", "net.minecraft.world.entity.vehicle.Boat"),
            Map.entry("minecraft:mangrove_chest_boat", "net.minecraft.world.entity.vehicle.ChestBoat"),
            Map.entry("minecraft:marker", "net.minecraft.world.entity.Marker"),
            Map.entry("minecraft:minecart", "net.minecraft.world.entity.vehicle.Minecart"),
            Map.entry("minecraft:mooshroom", "net.minecraft.world.entity.animal.MushroomCow"),
            Map.entry("minecraft:mule", "net.minecraft.world.entity.animal.horse.Mule"),
            Map.entry("minecraft:oak_boat", "net.minecraft.world.entity.vehicle.Boat"),
            Map.entry("minecraft:oak_chest_boat", "net.minecraft.world.entity.vehicle.ChestBoat"),
            Map.entry("minecraft:ocelot", "net.minecraft.world.entity.animal.Ocelot"),
            Map.entry("minecraft:ominous_item_spawner", "net.minecraft.world.entity.OminousItemSpawner"),
            Map.entry("minecraft:painting", "net.minecraft.world.entity.decoration.Painting"),
            Map.entry("minecraft:pale_oak_boat", "net.minecraft.world.entity.vehicle.Boat"),
            Map.entry("minecraft:pale_oak_chest_boat", "net.minecraft.world.entity.vehicle.ChestBoat"),
            Map.entry("minecraft:panda", "net.minecraft.world.entity.animal.Panda"),
            Map.entry("minecraft:parrot", "net.minecraft.world.entity.animal.Parrot"),
            Map.entry("minecraft:phantom", "net.minecraft.world.entity.monster.Phantom"),
            Map.entry("minecraft:pig", "net.minecraft.world.entity.animal.Pig"),
            Map.entry("minecraft:piglin", "net.minecraft.world.entity.monster.piglin.Piglin"),
            Map.entry("minecraft:piglin_brute", "net.minecraft.world.entity.monster.piglin.PiglinBrute"),
            Map.entry("minecraft:pillager", "net.minecraft.world.entity.monster.Pillager"),
            Map.entry("minecraft:polar_bear", "net.minecraft.world.entity.animal.PolarBear"),
            Map.entry("minecraft:splash_potion", "net.minecraft.world.entity.projectile.ThrownSplashPotion"),
            Map.entry("minecraft:lingering_potion", "net.minecraft.world.entity.projectile.ThrownLingeringPotion"),
            Map.entry("minecraft:pufferfish", "net.minecraft.world.entity.animal.Pufferfish"),
            Map.entry("minecraft:rabbit", "net.minecraft.world.entity.animal.Rabbit"),
            Map.entry("minecraft:ravager", "net.minecraft.world.entity.monster.Ravager"),
            Map.entry("minecraft:salmon", "net.minecraft.world.entity.animal.Salmon"),
            Map.entry("minecraft:sheep", "net.minecraft.world.entity.animal.sheep.Sheep"),
            Map.entry("minecraft:shulker", "net.minecraft.world.entity.monster.Shulker"),
            Map.entry("minecraft:shulker_bullet", "net.minecraft.world.entity.projectile.ShulkerBullet"),
            Map.entry("minecraft:silverfish", "net.minecraft.world.entity.monster.Silverfish"),
            Map.entry("minecraft:skeleton", "net.minecraft.world.entity.monster.Skeleton"),
            Map.entry("minecraft:skeleton_horse", "net.minecraft.world.entity.animal.horse.SkeletonHorse"),
            Map.entry("minecraft:slime", "net.minecraft.world.entity.monster.Slime"),
            Map.entry("minecraft:small_fireball", "net.minecraft.world.entity.projectile.SmallFireball"),
            Map.entry("minecraft:sniffer", "net.minecraft.world.entity.animal.sniffer.Sniffer"),
            Map.entry("minecraft:snowball", "net.minecraft.world.entity.projectile.Snowball"),
            Map.entry("minecraft:snow_golem", "net.minecraft.world.entity.animal.SnowGolem"),
            Map.entry("minecraft:spawner_minecart", "net.minecraft.world.entity.vehicle.MinecartSpawner"),
            Map.entry("minecraft:spectral_arrow", "net.minecraft.world.entity.projectile.SpectralArrow"),
            Map.entry("minecraft:spider", "net.minecraft.world.entity.monster.Spider"),
            Map.entry("minecraft:spruce_boat", "net.minecraft.world.entity.vehicle.Boat"),
            Map.entry("minecraft:spruce_chest_boat", "net.minecraft.world.entity.vehicle.ChestBoat"),
            Map.entry("minecraft:squid", "net.minecraft.world.entity.animal.Squid"),
            Map.entry("minecraft:stray", "net.minecraft.world.entity.monster.Stray"),
            Map.entry("minecraft:strider", "net.minecraft.world.entity.monster.Strider"),
            Map.entry("minecraft:tadpole", "net.minecraft.world.entity.animal.frog.Tadpole"),
            Map.entry("minecraft:text_display", "net.minecraft.world.entity.Display$TextDisplay"),
            Map.entry("minecraft:tnt", "net.minecraft.world.entity.item.PrimedTnt"),
            Map.entry("minecraft:tnt_minecart", "net.minecraft.world.entity.vehicle.MinecartTNT"),
            Map.entry("minecraft:trader_llama", "net.minecraft.world.entity.animal.horse.TraderLlama"),
            Map.entry("minecraft:trident", "net.minecraft.world.entity.projectile.ThrownTrident"),
            Map.entry("minecraft:tropical_fish", "net.minecraft.world.entity.animal.TropicalFish"),
            Map.entry("minecraft:turtle", "net.minecraft.world.entity.animal.Turtle"),
            Map.entry("minecraft:vex", "net.minecraft.world.entity.monster.Vex"),
            Map.entry("minecraft:villager", "net.minecraft.world.entity.npc.Villager"),
            Map.entry("minecraft:vindicator", "net.minecraft.world.entity.monster.Vindicator"),
            Map.entry("minecraft:wandering_trader", "net.minecraft.world.entity.npc.WanderingTrader"),
            Map.entry("minecraft:warden", "net.minecraft.world.entity.monster.warden.Warden"),
            Map.entry("minecraft:wind_charge", "net.minecraft.world.entity.projectile.windcharge.WindCharge"),
            Map.entry("minecraft:witch", "net.minecraft.world.entity.monster.Witch"),
            Map.entry("minecraft:wither", "net.minecraft.world.entity.boss.wither.WitherBoss"),
            Map.entry("minecraft:wither_skeleton", "net.minecraft.world.entity.monster.WitherSkeleton"),
            Map.entry("minecraft:wither_skull", "net.minecraft.world.entity.projectile.WitherSkull"),
            Map.entry("minecraft:wolf", "net.minecraft.world.entity.animal.wolf.Wolf"),
            Map.entry("minecraft:zoglin", "net.minecraft.world.entity.monster.Zoglin"),
            Map.entry("minecraft:zombie", "net.minecraft.world.entity.monster.Zombie"),
            Map.entry("minecraft:zombie_horse", "net.minecraft.world.entity.animal.horse.ZombieHorse"),
            Map.entry("minecraft:zombie_villager", "net.minecraft.world.entity.monster.ZombieVillager"),
            Map.entry("minecraft:zombified_piglin", "net.minecraft.world.entity.monster.ZombifiedPiglin"),
            Map.entry("minecraft:player", "net.minecraft.world.entity.player.Player"),
            Map.entry("minecraft:fishing_bobber", "net.minecraft.world.entity.projectile.FishingHook")
    );

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, VanillaHostilityTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static String classify(String type, Class<?> entity) {
        boolean enemy = load("net.minecraft.world.entity.monster.Enemy").isAssignableFrom(entity);
        return EntityJson.hostility(type, new EntityJson.Traits(
                load("net.minecraft.world.entity.player.Player").isAssignableFrom(entity),
                load("net.minecraft.world.entity.item.ItemEntity").isAssignableFrom(entity),
                load("net.minecraft.world.entity.Mob").isAssignableFrom(entity),
                load("net.minecraft.world.entity.NeutralMob").isAssignableFrom(entity),
                enemy, enemy));
    }

    /** {@code type -> hostility} from the protocol's table; unlisted types are "other". */
    private static Map<String, String> published() throws Exception {
        Map<String, String> table = new HashMap<>();
        var row = Pattern.compile("^\\| `(hostile|neutral|passive)` \\| (?:every other vanilla mob: )?(.+) \\|$");
        for (String line : Files.readAllLines(Path.of("protocol/v1.md"))) {
            var match = row.matcher(line);
            if (!match.matches()) continue;
            Arrays.stream(match.group(2).split(", ")).forEach(name -> table.put("minecraft:" + name, match.group(1)));
        }
        return table;
    }

    @Test
    void everyVanillaTypeMatchesThePublishedTable() throws Exception {
        Map<String, String> published = published();
        assertTrue(TYPES.keySet().containsAll(published.keySet()), "the table names only real types");
        Map<String, String> expected = new TreeMap<>(), actual = new TreeMap<>();
        TYPES.forEach((type, className) -> {
            String fallback = type.equals("minecraft:player") ? "player" : type.equals("minecraft:item") ? "item" : "other";
            expected.put(type, published.getOrDefault(type, fallback));
            actual.put(type, classify(type, load(className)));
        });
        assertEquals(expected, actual);
    }

    @Test
    void theNeutralTableOnlyNamesVanillaTypes() {
        assertTrue(TYPES.keySet().containsAll(EntityJson.VANILLA_NEUTRAL));
    }
}
