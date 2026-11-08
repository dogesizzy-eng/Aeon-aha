package com.example.aha;

import javax.annotation.Nullable;

/**
 * Shared (client + server) description of every Aha action and its timeline.
 *
 * <p>The SERVER owns the clock. It counts ticks from the moment the animation starts and registers
 * damage on the tick numbers in {@link #impactTicks()}. The CLIENT only plays visuals (GeckoLib
 * animation, camera, overlay) against the same numbers. Because both sides read this one table,
 * animation and damage cannot drift apart. When you re-time an animation in Blockbench, change it
 * here and nowhere else.
 *
 * <p>Impact index meaning:
 * <ul>
 *   <li>BASIC_ATTACK: 0 = slash lands</li>
 *   <li>SKILL: 0 = ally buff beat, 1 = AoE cascade blast</li>
 *   <li>ULTIMATE: 0 = main single-target strike, 1..n = "Bounce" projectiles</li>
 *   <li>ELATION_SKILL: 0..3 = damage cards hit every enemy, 4 = the rose explodes (True DMG)</li>
 *   <li>TECHNIQUE: 0 = the Misjudgment Dimension opens</li>
 * </ul>
 *
 * <p>Timings are read off the "Aeon Aha ALL Animations Showcase" clip (20 ticks = 1 s):
 * <ul>
 *   <li>Basic Attack (1:03-1:06, 3.0 s): heart crest forms on the enemy at 1.0 s, bursts at 1.75 s = tick 35.</li>
 *   <li>Skill (1:06-1:24, 18.0 s): ally receives the buff at 6.75 s = tick 135 (white flash at 6.0 s), Aha's grin ball
 *       drops 9.0-11.25 s, the neon-heart burst lands at 13.5 s = tick 270, confetti finish at 17.25 s.</li>
 *   <li>Ultimate (1:24-1:43, 19.0 s): crown-stage hold 3.75-10.5 s, giant card 12.0 s, card strike at 14.25 s = tick 285,
 *       light-ray bursts 15.0-16.5 s = ticks 300..330, hearts finale at 18.5 s.</li>
 * </ul>
 * <p>Elation Skill (19.0 s) and Technique Intro (4.0 s) are separate actions with their own animation, shots and keys:
 * <ul>
 *   <li>Elation Skill: Aha's intro 0.75-2.25 s, the Punchline cards gather 2.25-7.5 s, the damage cards fly into the
 *       enemies 7.5-8.7 s (ticks 150, 158, 166, 174), the cards fuse into a rose 9.0-10.5 s, it blooms and detonates at
 *       12.0 s = tick 240, then the red checker-board finale until 19.0 s.</li>
 *   <li>Technique Intro: wand twirl 0-1.0 s, raised 1.0-2.0 s, the dimension opens at 2.0 s = tick 40.</li>
 * </ul>
 * Only Aha's own beats are modelled; other characters' cameos in the clip are intentionally left out.
 */
public enum AhaSkill {

    //            animation trigger    ticks  impact ticks                                   wishCost wishGain needsTarget usesTurn
    BASIC_ATTACK ("basic_slash",        60,    new int[] {35},                                0,       1,       true,       true),
    SKILL        ("skill_aoe_cascade",  360,   new int[] {135, 270},                          0,       2,       true,       true),
    ULTIMATE     ("ultimate_faces",     380,   new int[] {285, 300, 306, 312, 318, 324, 330}, 8,       0,       true,       false),
    // Aha Instant finisher: not a turn of its own, so it never consumes a Faces of Elation turn. Talent: +1 Wish Power.
    ELATION_SKILL("elation_skill",      380,   new int[] {150, 158, 166, 174, 240},           0,       1,       true,       false),
    // Technique: aimless (no target needed), cooldown handled by the server session + staff item cooldown.
    TECHNIQUE    ("technique_intro",    80,    new int[] {40},                                0,       0,       false,      false);

    private static final AhaSkill[] VALUES = values();

    private final String trigger;
    private final int durationTicks;
    private final int[] impactTicks;
    private final int wishCost;
    private final int wishGain;
    private final boolean needsTarget;
    private final boolean usesTurn;

    AhaSkill(String trigger, int durationTicks, int[] impactTicks, int wishCost, int wishGain,
             boolean needsTarget, boolean usesTurn) {
        this.trigger = trigger;
        this.durationTicks = durationTicks;
        this.impactTicks = impactTicks;
        this.wishCost = wishCost;
        this.wishGain = wishGain;
        this.needsTarget = needsTarget;
        this.usesTurn = usesTurn;
    }

    /** Name of the GeckoLib triggerable animation; the animation itself is "animation.aha.&lt;trigger&gt;". */
    public String trigger() { return trigger; }

    /** Total length of the action in ticks (the Blockbench animation must be this long / 20 seconds). */
    public int durationTicks() { return durationTicks; }

    /** Ticks (relative to animation start) on which the server registers hits / effects. */
    public int[] impactTicks() { return impactTicks; }

    public int wishCost() { return wishCost; }

    public int wishGain() { return wishGain; }

    /** False for the Technique: the client sends targetId -1 and the server skips the target checks. */
    public boolean needsTarget() { return needsTarget; }

    /** True if using it spends one Faces of Elation turn (Basic and Skill). Ultimate, Elation Skill and Technique do not. */
    public boolean usesTurn() { return usesTurn; }

    /** Bounds-checked lookup so a malformed packet can never throw. */
    @Nullable
    public static AhaSkill byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : null;
    }
}
