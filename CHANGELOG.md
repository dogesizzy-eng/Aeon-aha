# Elation Skill + Technique Intro (protocol 2)

New separate actions (AhaSkill.ELATION_SKILL, AhaSkill.TECHNIQUE), each with its own key, packet value, animation, camera shots:
- V  Elation Skill: Aha Instant finisher. Needs >= 10 Punchlines and consumes all of them.
      0.75-2.25 s intro, 2.25-7.5 s cards gather, ticks 150/158/166/174 four damage cards hit every enemy (60% Elation DMG total,
      scaled +5% per Punchline), 9.0-10.5 s the cards fuse into a rose, tick 240 the rose detonates: True DMG = 20% of the total
      dealt by this Aha Instant, split equally. +1 Wish Power, Faces of Elation recovers 10% of the Punchlines (Bliss).
      Does not consume a Faces of Elation turn. Elation Debt is applied to every enemy hit. 380 ticks (19 s).
- B  Technique Intro: 80 ticks, no target needed, dimension opens on tick 40. 30 s cooldown (session + staff). Staff right-click
      now starts this same cutscene.
- Radial menu has 5 wedges (keys 1-5). C2S packet is unchanged; protocol string bumped to "2".
- New damage type aha:quantum_elation_true (+ bypasses_armor/effects/resistance/enchantments tags).
- New FX: fx_rose (geo, texture, animation.aha_fx.rose). New avatar animations: elation_skill, technique_intro.
- New GUI icons: icon_elation.png, icon_technique.png. Sounds: elation_card / elation_bloom / elation_rose (placeholders).
