-- Add new toggle state columns with default values
ALTER TABLE areas ADD COLUMN toggle_states TEXT DEFAULT '{}';
ALTER TABLE areas ADD COLUMN default_toggle_states TEXT DEFAULT '{}';
ALTER TABLE areas ADD COLUMN inherited_toggle_states TEXT DEFAULT '{}';

-- Migrate existing settings to toggle_states format
UPDATE areas 
SET toggle_states = (
    CASE 
        WHEN settings IS NOT NULL THEN settings
        ELSE '{}'
    END
);
