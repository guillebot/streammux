package main

import (
	"errors"
	"fmt"
	"regexp"
	"strings"
	"unicode"
)

// CEL compiled by job-contracts JoinKeyCel (strings + regex extensions).
const (
	celIdentity   = "key"
	celAcctnum12  = `size(key.split("-")) == 3 ? key.split("-")[0] + key.split("-")[1] + (size(key.split("-")[2]) > 2 ? key.split("-")[2].substring(0, 2) : (size(key.split("-")[2]) == 2 ? key.split("-")[2] : "0" + key.split("-")[2])) : (size(regex.replace(key, "[^0-9]", "")) >= 12 ? regex.replace(key, "[^0-9]", "").substring(0, 12) : regex.replace(key, "[^0-9]", ""))`
	celDigitsOnly = `size(regex.replace(key, "[^0-9]", "")) >= 12 ? regex.replace(key, "[^0-9]", "").substring(0, 12) : regex.replace(key, "[^0-9]", "")`
	// Snapshot from prod get_job json-enricher-csg-osp-1 (jobVersion 1, 2026-09-26).
	celDeployedHyphenOnly = `size(key.split("-")) == 3 ? key.split("-")[0] + key.split("-")[1] + (size(key.split("-")[2]) >= 2 ? key.split("-")[2] : "0" + key.split("-")[2]) : key`
)

var nonDigits = regexp.MustCompile(`[^0-9]+`)

type enricherPreset struct {
	ID          string `json:"id"`
	JoinKeyCel  string `json:"join_key_cel"`
	Description string `json:"description"`
}

func enricherPresets() []enricherPreset {
	return []enricherPreset{
		{
			ID:          "acctnum-12",
			JoinKeyCel:  celAcctnum12,
			Description: "Hyphenated NNNN-NNNNNN-N(N): parts[0]+parts[1]+last padded/truncated to 2. Otherwise strip non-digits and take 12. Matches CSG AccountNum → 12-digit custdata keys.",
		},
		{
			ID:          "identity",
			JoinKeyCel:  celIdentity,
			Description: "Use the extracted field as the lookup key with no transform.",
		},
		{
			ID:          "digits-only",
			JoinKeyCel:  celDigitsOnly,
			Description: "Strip non-digits, then take the first 12 digits (or all digits if fewer). Does not pad a 1-digit check segment; use acctnum-12 for hyphenated accounts.",
		},
	}
}

func resolveCelPreset(id string) (enricherPreset, error) {
	want := strings.ToLower(strings.TrimSpace(id))
	if want == "" {
		want = "acctnum-12"
	}
	for _, p := range enricherPresets() {
		if p.ID == want {
			return p, nil
		}
	}
	return enricherPreset{}, fmt.Errorf("unknown cel_preset %q (use acctnum-12, identity, or digits-only)", id)
}

func getEnricherTemplatePayload() map[string]any {
	return map[string]any{
		"doc":                 "docs/enricher-guide.md",
		"job_type":            "JSON_ENRICHER",
		"output_topic_prefix": "net.optimum.experimental.streamlens.streammux.",
		"privacy":             "If input or lookup is Restricted, the output topic is Restricted. Verify hits with counts only; never print payloads.",
		"account_num_shapes": []map[string]string{
			{"shape": "13-char hyphenated", "pattern": "NNNN-NNNNNN-N", "note": "pad last segment to 2 digits"},
			{"shape": "14-char hyphenated", "pattern": "NNNN-NNNNNN-NN", "note": "last segment already 2 digits"},
			{"shape": "15-char mixed", "pattern": "12 digits + 3 letters", "note": "strip non-digits, take 12"},
		},
		"deployed_csg_osp_cel": map[string]any{
			"job_id":       "json-enricher-csg-osp-1",
			"job_version":  1,
			"updated_at":   "2026-09-26T01:07:14.150050473Z",
			"join_key_cel": celDeployedHyphenOnly,
			"note":         "Hyphenated pad only; 15-char form is identity and will miss 12-digit lookup keys. Re-read get_job before relying on this snapshot.",
		},
		"presets":            enricherPresets(),
		"build_enricher_job": "Pass input_topic, lookup_topic, key_field, source, output_topic, optional cel_preset.",
		"next": []string{
			"normalize_key_preview on synthetic samples",
			"build_enricher_job",
			"validate_job",
			"create_job with apply=true only after review",
		},
	}
}

func buildEnricherJob(args map[string]any) (map[string]any, error) {
	inputTopic := strings.TrimSpace(argString(args, "input_topic"))
	lookupTopic := strings.TrimSpace(argString(args, "lookup_topic"))
	keyField := strings.TrimSpace(argString(args, "key_field"))
	source := strings.TrimSpace(argString(args, "source"))
	outputTopic := strings.TrimSpace(argString(args, "output_topic"))
	if inputTopic == "" || lookupTopic == "" || keyField == "" || source == "" || outputTopic == "" {
		return nil, errors.New("input_topic, lookup_topic, key_field, source, and output_topic are required")
	}
	preset, err := resolveCelPreset(argString(args, "cel_preset"))
	if err != nil {
		return nil, err
	}
	enrichmentName := strings.TrimSpace(argString(args, "enrichment_name"))
	if enrichmentName == "" {
		enrichmentName = "custdata"
	}
	jobID := strings.TrimSpace(argString(args, "job_id"))
	if jobID == "" {
		jobID = "json-enricher-" + slugPart(source) + "-1"
	}
	site := strings.TrimSpace(argString(args, "site_affinity"))
	if site == "" {
		site = "site-a"
	}
	desired := strings.TrimSpace(argString(args, "desired_state"))
	if desired == "" {
		desired = "PAUSED"
	}
	cfg := map[string]any{
		"inputTopic":     inputTopic,
		"outputTopic":    outputTopic,
		"source":         source,
		"joinKeyPath":    keyField,
		"joinKeyCel":     preset.JoinKeyCel,
		"lookupTopic":    lookupTopic,
		"enrichmentName": enrichmentName,
	}
	if bs := strings.TrimSpace(argString(args, "bootstrap_servers")); bs != "" {
		cfg["streamProperties"] = map[string]any{"bootstrap.servers": bs}
	}

	warnings := []string{}
	if !strings.HasPrefix(outputTopic, "net.optimum.experimental.streamlens.streammux.") {
		warnings = append(warnings, "output_topic should use prefix net.optimum.experimental.streamlens.streammux. (and pass the output allowlist)")
	}
	warnings = append(warnings, "Output inherits Restricted if input or lookup is Restricted. Do not log payloads.")
	warnings = append(warnings, "This tool does not persist the job. Call validate_job, then create_job with apply=true.")

	return map[string]any{
		"cel_preset": preset.ID,
		"warnings":   warnings,
		"job": map[string]any{
			"jobId":        jobID,
			"jobVersion":   0,
			"jobType":      "JSON_ENRICHER",
			"desiredState": desired,
			"priority":     1,
			"siteAffinity": site,
			"leasePolicy": map[string]any{
				"heartbeatIntervalSeconds": 10,
				"leaseDurationSeconds":     30,
				"claimBackoffMillis":       5000,
				"allowFailover":            true,
			},
			"parallelism":        1,
			"jsonEnricherConfig": cfg,
			"labels":             map[string]any{"team": "mux"},
			"tags":               []string{"json-enricher", slugPart(source)},
		},
	}, nil
}

func normalizeKeyPreview(args map[string]any) (map[string]any, error) {
	preset, err := resolveCelPreset(argString(args, "cel_preset"))
	if err != nil {
		return nil, err
	}
	samples, err := argStringSliceAllowString(args, "samples")
	if err != nil {
		return nil, err
	}
	if len(samples) == 0 {
		return nil, errors.New("samples is required (array of synthetic strings; never pass customer values)")
	}
	results := make([]map[string]any, 0, len(samples))
	for _, raw := range samples {
		out, dropped := applyCelPreset(preset.ID, raw)
		row := map[string]any{
			"input":   raw,
			"output":  out,
			"dropped": dropped,
		}
		results = append(results, row)
	}
	return map[string]any{
		"cel_preset":   preset.ID,
		"join_key_cel": preset.JoinKeyCel,
		"note":         "Local preview of the preset algorithm (not a Kafka read). Use synthetic strings only.",
		"results":      results,
	}, nil
}

func applyCelPreset(preset, key string) (string, bool) {
	switch preset {
	case "identity":
		if strings.TrimSpace(key) == "" {
			return "", true
		}
		return key, false
	case "digits-only":
		return takeDigits12(key)
	default: // acctnum-12
		if parts := strings.Split(key, "-"); len(parts) == 3 {
			last := parts[2]
			switch {
			case len(last) > 2:
				last = last[:2]
			case len(last) == 2:
				// already two
			default:
				last = "0" + last
			}
			out := parts[0] + parts[1] + last
			if strings.TrimSpace(out) == "" {
				return "", true
			}
			return out, false
		}
		return takeDigits12(key)
	}
}

func takeDigits12(key string) (string, bool) {
	digits := nonDigits.ReplaceAllString(key, "")
	if digits == "" {
		return "", true
	}
	if len(digits) >= 12 {
		return digits[:12], false
	}
	return digits, false
}

func slugPart(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
		} else if r == '-' || r == '_' {
			b.WriteByte('-')
		}
	}
	out := strings.Trim(b.String(), "-")
	if out == "" {
		return "job"
	}
	return out
}

func argStringSliceAllowString(args map[string]any, key string) ([]string, error) {
	if args == nil {
		return nil, errors.New(key + " is required")
	}
	v, ok := args[key]
	if !ok || v == nil {
		return nil, errors.New(key + " is required")
	}
	switch t := v.(type) {
	case []any:
		out := make([]string, 0, len(t))
		for _, item := range t {
			out = append(out, fmt.Sprint(item))
		}
		return out, nil
	case []string:
		return t, nil
	case string:
		s := strings.TrimSpace(t)
		if s == "" {
			return nil, errors.New(key + " is required")
		}
		return []string{s}, nil
	default:
		return nil, fmt.Errorf("%s must be a string array", key)
	}
}
