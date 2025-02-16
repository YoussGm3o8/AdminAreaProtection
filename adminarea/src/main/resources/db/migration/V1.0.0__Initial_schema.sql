CREATE TABLE IF NOT EXISTS areas (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    world TEXT NOT NULL,
    x_min INTEGER NOT NULL,
    x_max INTEGER NOT NULL,
    y_min INTEGER NOT NULL,
    y_max INTEGER NOT NULL,
    z_min INTEGER NOT NULL,
    z_max INTEGER NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    show_title BOOLEAN NOT NULL DEFAULT 0,
    settings TEXT
);

CREATE TABLE IF NOT EXISTS luckpermsoverrides (
    area TEXT NOT NULL,
    target TEXT NOT NULL,
    overrides TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (area, target)
);

CREATE INDEX IF NOT EXISTS idx_areas_name ON areas(name);
CREATE INDEX IF NOT EXISTS idx_luckpermsoverrides_area ON luckpermsoverrides(area);
