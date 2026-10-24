package com.mudworm.macro;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mudworm.macro.path.Pathfinder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;

/** Entry point: events, the toggle key, /mudworm commands, HUD and boxes. */
public final class MudwormMod implements ClientModInitializer {
    private static final Macro M = Macro.INSTANCE;
    private static volatile long blatantUnlockedAt;

    @Override
    public void onInitializeClient() {
        Config.load();
        Updater.cleanOld();
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> Updater.onJoin());
        SpotScanner.loadSites();

        KeyMapping toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mudwormmacro.toggle",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KeyMapping.Category.MISC));

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            while (toggle.consumeClick()) {
                if (mc.player != null && mc.screen == null) M.toggle(mc);
            }
            SpotScanner.tick(mc);
            M.tick(mc);
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> M.onChat(message.getString()));

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("mudwormmacro", "status"), Hud::render);

        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            if (M.running() && mc.player != null && mc.screen == null) M.aim.frame(mc, mc.player);
            if (Config.esp) drawBoxes();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = lit("mudworm").executes(c -> help());
            root.then(lit("start").executes(c -> run(mc -> M.start(mc))));
            root.then(lit("stop").executes(c -> run(mc -> M.stop(mc, null))));
            root.then(lit("toggle").executes(c -> run(M::toggle)));
            root.then(lit("help").executes(c -> help()));
            root.then(lit("dev").executes(c -> run(Dev::run)));
            root.then(lit("version").executes(c -> {
                Chat.msg(Updater.versionInfo());
                return 1;
            }));
            root.then(lit("update")
                    .executes(c -> {
                        Updater.run(false, true);
                        return 1;
                    })
                    .then(lit("check").executes(c -> {
                        Updater.run(false, false);
                        return 1;
                    })));
            root.then(lit("status").executes(c -> {
                Chat.msg(M.status() + " | spots known: " + SpotScanner.SPOTS.size() + " | sites: "
                        + SpotScanner.SITES.size() + " | done: " + M.spotsDone + " | warps: " + M.warps);
                return 1;
            }));
            root.then(lit("spots").executes(c -> {
                if (SpotScanner.SPOTS.isEmpty()) Chat.msg("No spots in sight.");
                for (BlockPos b : SpotScanner.SPOTS.keySet()) Chat.msg("Spot at " + b.toShortString());
                return 1;
            }));
            root.then(lit("esp").executes(c -> flag("Boxes", Config.esp = !Config.esp)));
            root.then(lit("hud").executes(c -> flag("Status panel", Config.hud = !Config.hud)));
            root.then(lit("debug").executes(c -> flag("Debug messages", Config.debug = !Config.debug)));
            root.then(lit("strict").executes(c -> flag("Strict ray (only air lets the etherwarp through)", Config.strictRay = !Config.strictRay)));
            root.then(lit("stuckwarp").executes(c -> flag("Warp when stuck", Config.unstuckWarp = !Config.unstuckWarp)));
            root.then(lit("stuckcmd").then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("command",
                    StringArgumentType.greedyString()).executes(c -> {
                Config.stuckCommand = StringArgumentType.getString(c, "command").trim();
                Config.save();
                Chat.msg("When stuck for " + Config.stuckSeconds + " s it runs: /" + Config.stuckCommand);
                return 1;
            })));
            root.then(lit("stucktime").then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("seconds",
                    IntegerArgumentType.integer(1, 60)).executes(c -> {
                Config.stuckSeconds = IntegerArgumentType.getInteger(c, "seconds");
                Config.save();
                Chat.msg("Stuck timer: " + Config.stuckSeconds + " s.");
                return 1;
            })));
            root.then(lit("failsafe").executes(c -> flag("Stop when teleported by something else", Config.stopOnForeignTeleport = !Config.stopOnForeignTeleport)));
            root.then(lit("range").then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("blocks",
                    IntegerArgumentType.integer(10, 61)).executes(c -> {
                Config.baseRange = IntegerArgumentType.getInteger(c, "blocks");
                Config.save();
                Chat.msg("Base etherwarp range: " + Config.baseRange + " (+ tuners on the item).");
                return 1;
            })));
            root.then(lit("ethercost").then(RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("blocks",
                    DoubleArgumentType.doubleArg(1, 100)).executes(c -> {
                Config.etherCost = DoubleArgumentType.getDouble(c, "blocks");
                Config.save();
                Chat.msg("Hop cost: " + Config.etherCost);
                return 1;
            })));
            root.then(lit("area")
                    .then(lit("marsh").executes(c -> {
                        Config.areaMinX = -708;
                        Config.areaMaxX = -603;
                        Config.areaMinZ = -46;
                        Config.areaMaxZ = 77;
                        Config.save();
                        Chat.msg("Patrol area: Moonglade Marsh.");
                        return 1;
                    }))
                    .then(lit("here").then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("radius",
                            IntegerArgumentType.integer(10, 400)).executes(c -> {
                        var p = Minecraft.getInstance().player;
                        if (p == null) return 0;
                        int r = IntegerArgumentType.getInteger(c, "radius");
                        Config.areaMinX = p.getBlockX() - r;
                        Config.areaMaxX = p.getBlockX() + r;
                        Config.areaMinZ = p.getBlockZ() - r;
                        Config.areaMaxZ = p.getBlockZ() + r;
                        Config.save();
                        Chat.msg("Patrol area: " + (2 * r) + "x" + (2 * r) + " around you.");
                        return 1;
                    }))));
            root.then(lit("blacklist")
                    .executes(c -> {
                        Chat.msg("Blacklisted spots this lobby: " + SpotScanner.BLACKLIST.size() + " " + SpotScanner.BLACKLIST);
                        return 1;
                    })
                    .then(lit("clear").executes(c -> {
                        SpotScanner.BLACKLIST.clear();
                        Chat.msg("Blacklist cleared.");
                        return 1;
                    })));
            root.then(lit("axe")
                    .executes(c -> {
                        // take the name from the item in the hand right now, and turn the axe on
                        var pl = Minecraft.getInstance().player;
                        if (pl == null) return 0;
                        var held = pl.getInventory().getItem(pl.getInventory().getSelectedSlot());
                        if (held.isEmpty()) {
                            Chat.msg("Hold the axe in your hand first, then run /mudworm axe.");
                            return 0;
                        }
                        if (pl.getInventory().getSelectedSlot() == Macro.aotvSlot(pl)) {
                            Chat.msg("That is the Aspect of the Void. Hold the axe you want to mine with, then run /mudworm axe.");
                            return 0;
                        }
                        Config.axeName = held.getHoverName().getString().replaceAll("§.", "").trim();
                        Config.useAxe = true;
                        Config.save();
                        Chat.msg("Axe on: using '" + Config.axeName + "' (hotbar slot " + (pl.getInventory().getSelectedSlot() + 1)
                                + ") to mine spots. /mudworm axe off turns it off, /mudworm axe name <text> sets a shorter name.");
                        return 1;
                    })
                    .then(lit("off").executes(c -> {
                        Config.useAxe = false;
                        Config.save();
                        Chat.msg("Axe off.");
                        return 1;
                    }))
                    .then(lit("name").then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("text",
                            StringArgumentType.greedyString()).executes(c -> {
                        Config.axeName = StringArgumentType.getString(c, "text").trim();
                        Config.save();
                        var pl = Minecraft.getInstance().player;
                        Chat.msg("Axe name set to '" + Config.axeName + "' (any hotbar item whose name contains it, not case sensitive)"
                                + (pl != null && Macro.slotByName(pl, Config.axeName) < 0 ? " - not found in the hotbar right now." : "."));
                        return 1;
                    }))));
            root.then(lit("scoottime").then(RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("seconds",
                    DoubleArgumentType.doubleArg(0.2, 10)).executes(c -> {
                Config.scootSeconds = DoubleArgumentType.getDouble(c, "seconds");
                Config.save();
                Chat.msg("One scoot lasts at most " + Config.scootSeconds + " s, then the not-moving timer starts again.");
                return 1;
            })));
            root.then(lit("blatant")
                    .executes(c -> {
                        Chat.msg("Blatant mode is " + (Config.blatant ? "ON" : "off") + ". /mudworm blatant unlock  to start turning it on.");
                        return 1;
                    })
                    .then(lit("unlock").executes(c -> {
                        blatantUnlockedAt = System.currentTimeMillis();
                        Chat.msg("§cBLATANT MODE: etherwarps will send their aim to the server WITHOUT moving your camera.");
                        Chat.msg("§cThat is far easier for anti-cheat and other players to spot than normal use. A ban is much more likely.");
                        Chat.msg("To really turn it on, within 60 s type exactly:  /mudworm blatant on I understand this is blatant");
                        return 1;
                    }))
                    .then(lit("on").then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("phrase",
                            StringArgumentType.greedyString()).executes(c -> {
                        String phrase = StringArgumentType.getString(c, "phrase").trim();
                        boolean unlocked = System.currentTimeMillis() - blatantUnlockedAt < 60_000;
                        if (!unlocked) {
                            Chat.msg("First run /mudworm blatant unlock (and read the warning).");
                        } else if (!phrase.equalsIgnoreCase("I understand this is blatant")) {
                            Chat.msg("Wrong phrase. Type exactly:  /mudworm blatant on I understand this is blatant");
                        } else {
                            Config.blatant = true;
                            Chat.msg("§cBLATANT MODE ON (until you run /mudworm blatant off or restart the game).");
                        }
                        return 1;
                    })))
                    .then(lit("off").executes(c -> {
                        Config.blatant = false;
                        blatantUnlockedAt = 0;
                        Chat.msg("Blatant mode off.");
                        return 1;
                    })));
            root.then(lit("maxdist").then(RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("blocks",
                    DoubleArgumentType.doubleArg(10, 400)).executes(c -> {
                Config.maxTargetDistance = DoubleArgumentType.getDouble(c, "blocks");
                Config.save();
                Chat.msg("Only spots within " + Config.maxTargetDistance + " blocks are targeted; farther ones are ignored.");
                return 1;
            })));
            root.then(lit("minspots").then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("count",
                    IntegerArgumentType.integer(0, 50)).executes(c -> {
                Config.minSpots = IntegerArgumentType.getInteger(c, "count");
                Config.save();
                Chat.msg(Config.minSpots <= 0 ? "Lobby refresh is off." : "Fewer than " + Config.minSpots
                        + " live spots in the lobby: it runs /is then /warp murk for a new lobby (cooldown "
                        + Config.refreshCooldownSeconds + " s between switches).");
                return 1;
            })));
            root.then(lit("refreshcooldown").then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("seconds",
                    IntegerArgumentType.integer(5, 600)).executes(c -> {
                Config.refreshCooldownSeconds = IntegerArgumentType.getInteger(c, "seconds");
                Config.save();
                Chat.msg("At least " + Config.refreshCooldownSeconds + " s between lobby switches.");
                return 1;
            })));
            root.then(lit("scoot").then(RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("blocks",
                    DoubleArgumentType.doubleArg(0, 30)).executes(c -> {
                Config.scootRange = DoubleArgumentType.getDouble(c, "blocks");
                Config.save();
                Chat.msg(Config.scootRange <= 0 ? "Scoot is off." : "Scoot: spots within " + Config.scootRange
                        + " blocks are walked to with W/A/S/D (no head turning) instead of teleported to.");
                return 1;
            })));
            root.then(lit("hopdelay").then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("ms",
                    IntegerArgumentType.integer(0, 5000)).executes(c -> {
                Config.hopDelayMs = IntegerArgumentType.getInteger(c, "ms");
                Config.save();
                Chat.msg("Extra wait before every etherwarp: " + Config.hopDelayMs + " ms.");
                return 1;
            })));
            root.then(lit("yband")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("below", IntegerArgumentType.integer(0, 200))
                            .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("above", IntegerArgumentType.integer(0, 200))
                                    .executes(c -> {
                                        Config.landBelow = IntegerArgumentType.getInteger(c, "below");
                                        Config.landAbove = IntegerArgumentType.getInteger(c, "above");
                                        Config.save();
                                        Chat.msg("Hops may land " + Config.landBelow + " blocks below to " + Config.landAbove
                                                + " above the corridor between you and the spot.");
                                        return 1;
                                    }))));
            root.then(lit("sites").then(lit("clear").executes(c -> {
                SpotScanner.clearSites();
                Chat.msg("Forgot every remembered spot site.");
                return 1;
            })));
            root.then(lit("reload").executes(c -> {
                Config.load();
                Chat.msg("Settings reloaded.");
                return 1;
            }));
            dispatcher.register(root);
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> lit(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static int run(Consumer<Minecraft> action) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> action.accept(mc)); // after the chat screen closed
        return 1;
    }

    private static int flag(String what, boolean on) {
        Config.save();
        Chat.msg(what + ": " + (on ? "on" : "off"));
        return 1;
    }

    private static int help() {
        for (String s : List.of(
                "/mudworm start | stop | toggle (key K)",
                "/mudworm status | spots | dev (copies a full diagnostic report)",
                "/mudworm esp | hud | debug | strict | failsafe",
                "/mudworm stuckwarp | stuckcmd <command> | stucktime <seconds>",
                "/mudworm range <10-61> | ethercost <blocks>",
                "/mudworm area marsh | area here <radius>",
                "/mudworm update | update check | version",
                "/mudworm axe (takes the item in your hand) | axe off | axe name <text>  (use an axe for mining spots)",
                "/mudworm blatant  (silent etherwarp aim: asks for a 2-step confirmation, off at every start)",
                "/mudworm minspots <n> | refreshcooldown <seconds>  (fewer than n spots in the lobby: /is then /warp murk, 0 = off)",
                "/mudworm maxdist <blocks>  (spots farther than this are never targeted, default 120)",
                "/mudworm scoottime <seconds> | scoot <blocks>  (walk to spots this close with WASD, 0 = off)",
                "/mudworm hopdelay <ms>  (extra wait before each teleport, default 250)",
                "/mudworm yband <below> <above>  (how far hops may stray up/down from the way to the spot)",
                "/mudworm blacklist | blacklist clear",
                "/mudworm sites clear | reload")) Chat.msg(s);
        return 1;
    }

    /** Green boxes on spots, yellow on the current goal, cyan path, magenta etherwarp hops. */
    private static void drawBoxes() {
        Pathfinder.Goal g = M.goal();
        for (BlockPos s : SpotScanner.SPOTS.keySet()) {
            boolean cur = M.running() && g != null && s.equals(g.spot());
            boolean bad = SpotScanner.BLACKLIST.contains(s);
            boolean off = !M.isExposed(s); // walled in: only taken when nothing else is around
            int stroke = bad ? 0xFFFF4040 : off ? 0xFF808080 : cur ? 0xFFFFE030 : 0xFF30FF60;
            int fill = bad ? 0x30FF4040 : off ? 0x20808080 : cur ? 0x50FFE030 : 0x4030FF60;
            Gizmos.cuboid(s, GizmoStyle.strokeAndFill(stroke, cur ? 2.5f : 2f, fill)).setAlwaysOnTop();
        }
        if (!M.running()) return;
        var steps = M.steps();
        Vec3 prev = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.position();
        for (int i = M.stepIdx(); i < steps.size() && prev != null; i++) {
            var st = steps.get(i);
            Vec3 at = new Vec3(st.feet().getX() + 0.5, st.feet().getY() + 0.1, st.feet().getZ() + 0.5);
            if (st.shot() != null) {
                Gizmos.line(prev.add(0, 1.2, 0), at, 0xFFFF40FF, 2f).setAlwaysOnTop();
                Gizmos.cuboid(st.shot().target(), GizmoStyle.stroke(0xFFFF40FF, 2f)).setAlwaysOnTop();
            } else {
                Gizmos.line(prev.add(0, 0.1, 0), at, 0xFF30E0FF, 3f).setAlwaysOnTop();
            }
            prev = at;
        }
    }
}
