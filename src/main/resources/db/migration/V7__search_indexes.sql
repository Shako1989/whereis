-- Trigram indexes backing item search.
CREATE INDEX ix_items_name_trgm ON items USING gin (normalized_name gin_trgm_ops);
CREATE INDEX ix_items_desc_trgm ON items USING gin (COALESCE(description, '') gin_trgm_ops);
CREATE INDEX ix_locations_name_trgm ON locations USING gin (normalized_name gin_trgm_ops);
