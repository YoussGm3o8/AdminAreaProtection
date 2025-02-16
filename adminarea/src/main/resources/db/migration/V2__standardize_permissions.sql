
-- Update existing permissions to use standardized format
UPDATE areas 
SET settings = REPLACE(settings, '"allowBlockBreak":', '"area.block.break":');

UPDATE areas 
SET settings = REPLACE(settings, '"allowBlockPlace":', '"area.block.place":');

UPDATE areas 
SET settings = REPLACE(settings, '"allowPvP":', '"area.pvp":');

-- Add more UPDATE statements for other permission nodes that need to be standardized

-- You may want to create a backup before running these updates
