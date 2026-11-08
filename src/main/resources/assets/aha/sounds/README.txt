Custom audio (optional)
1. Put your own .ogg files here, e.g. ult_strike.ogg (Ogg Vorbis, mono is best for positional sound).
2. In ../sounds.json replace a placeholder entry, e.g.
   "ult_strike": { "sounds": [ "aha:ult_strike" ] }
Only use audio you have the right to use.

Sound events (all still vanilla placeholders in ../sounds.json, replace them with your own .ogg):
  basic_hit, skill_buff, skill_blast, ult_strike, ult_bounce, technique
  elation_card   - one Punchline card landing on an enemy (plays 4x per enemy)
  elation_bloom  - the cards fuse into a rose (tick 190 of the Elation Skill)
  elation_rose   - the rose detonates (tick 240)
GUI art you can swap (same file names): textures/gui/icon_basic|skill|ultimate|elation|technique.png (32x32)
and aha_mask_0..3.png (256x256, drawn during the Ultimate's crown stage).
