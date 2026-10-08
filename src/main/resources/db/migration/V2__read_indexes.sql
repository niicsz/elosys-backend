CREATE INDEX IF NOT EXISTS ix_people_cpf_pattern ON people (cpf text_pattern_ops) WHERE cpf IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_candidate_photo_person_year ON candidate_photo (person_id, year DESC);
CREATE INDEX IF NOT EXISTS ix_signal_ai_review_signal_time ON signal_ai_review (signal_id, reviewed_at DESC);

CREATE INDEX IF NOT EXISTS ix_campaign_donation_donor_year ON campaign_donation (donor_cpf_cnpj, year);
CREATE INDEX IF NOT EXISTS ix_campaign_expense_supplier_year ON campaign_expense (supplier_cpf_cnpj, year);

CREATE INDEX IF NOT EXISTS ix_earmark_beneficiary_doc ON parliamentary_earmark_beneficiary (beneficiary_doc);
CREATE INDEX IF NOT EXISTS ix_parliamentary_earmark_code ON parliamentary_earmark (earmark_code, id);

CREATE INDEX IF NOT EXISTS ix_social_account_handle ON social_account (handle);
CREATE INDEX IF NOT EXISTS ix_social_post_review_categories ON social_post_review USING gin (categories);
