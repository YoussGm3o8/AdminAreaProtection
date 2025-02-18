-- Update existing permission nodes to use standardized format
UPDATE areas 
SET settings = REPLACE(settings, '"allowBlockBreak":', '"area.block.break":');

UPDATE areas 
SET settings = REPLACE(settings, '"allowBlockPlace":', '"area.block.place":');

UPDATE areas 
SET settings = REPLACE(settings, '"allowInteract":', '"area.interact":');

UPDATE areas 
SET settings = REPLACE(settings, '"allowPvP":', '"area.pvp":');

UPDATE areas 
SET settings = REPLACE(settings, '"allowMobSpawn":', '"area.mob.spawn":');

UPDATE areas 
SET settings = REPLACE(settings, '"allowRedstone":', '"area.redstone":');

-- You may want to create a backup before running these updates
