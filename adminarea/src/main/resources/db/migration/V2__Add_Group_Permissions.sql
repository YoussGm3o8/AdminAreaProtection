CREATE TABLE IF NOT EXISTS group_permissions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    area_id INTEGER,
    group_name TEXT,
    permissions TEXT,
    FOREIGN KEY(area_id) REFERENCES areas(id) ON DELETE CASCADE,
    UNIQUE(area_id, group_name)
);

-- Add index for performance
CREATE INDEX idx_group_permissions_area_id ON group_permissions(area_id);
