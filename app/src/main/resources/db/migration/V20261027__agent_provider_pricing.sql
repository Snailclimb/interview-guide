ALTER TABLE llm_provider_config
  ADD COLUMN IF NOT EXISTS input_price_per_million NUMERIC(20, 8),
  ADD COLUMN IF NOT EXISTS output_price_per_million NUMERIC(20, 8),
  ADD COLUMN IF NOT EXISTS price_currency VARCHAR(3);
