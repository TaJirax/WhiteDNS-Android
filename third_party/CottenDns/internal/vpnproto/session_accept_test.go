// ==============================================================================
// CottenDNS
// Author: tajirax
// Github: https://github.com/TaJirax/CottenDns
// Year: 2026
// ==============================================================================

package vpnproto

import (
	"encoding/hex"
	"testing"
)

func samplePolicy() SessionAcceptClientPolicy {
	return SessionAcceptClientPolicy{
		MaxPacketDuplicationCount: 3,
		MaxSetupDuplicationCount:  5,
		MaxUploadMTU:              200,
		MaxDownloadMTU:            1400,
		MaxRxTxWorkers:            16,
		MinPingAggressiveInterval: 0.20,
		MaxPacketsPerBatch:        8,
		MaxARQWindowSize:          2000,
		MaxARQDataNackMaxGap:      64,
		MinCompressionMinSize:     120,
		MinARQInitialRTOSeconds:   0.25,
	}
}

// The app's compatibility preset sets LEGACY_SESSION_ID, which shifts the whole
// accept payload down a byte. The policy block must be found at the shifted
// offset, or a MasterDNS/StormDNS server's ceilings would be read out of
// alignment -- silently, since a garbage policy still decodes.
func TestPolicyOffsetFollowsConfiguredWireWidth(t *testing.T) {
	defer ConfigureLegacySessionID(false) // restore the process default

	for _, legacy := range []bool{false, true} {
		ConfigureLegacySessionID(legacy)

		wantBase := 8
		if legacy {
			wantBase = 7
		}
		if got := SessionAcceptBaseSize(); got != wantBase {
			t.Fatalf("legacy=%v: base size = %d, want %d", legacy, got, wantBase)
		}

		payload := EncodeSessionAccept(42, 0x11, 0x22, [4]byte{1, 2, 3, 4}, samplePolicy())
		if len(payload) != wantBase+SessionAcceptPolicyPayloadSize {
			t.Fatalf("legacy=%v: payload = %d bytes, want %d", legacy, len(payload), wantBase+SessionAcceptPolicyPayloadSize)
		}

		decoded, ok := DecodeSessionAcceptPolicy(payload)
		if !ok {
			t.Fatalf("legacy=%v: policy not found at the configured offset", legacy)
		}
		if decoded.MaxARQWindowSize != 2000 || decoded.MaxPacketsPerBatch != 8 {
			t.Fatalf("legacy=%v: policy misaligned: %+v", legacy, decoded)
		}
	}
}

// A server that states no ceilings must leave the accept payload byte-for-byte
// what it has always been, in either wire mode.
func TestNoPolicyLeavesAcceptPayloadUnchanged(t *testing.T) {
	defer ConfigureLegacySessionID(false)

	ConfigureLegacySessionID(false)
	if got := len(EncodeSessionAccept(300, 0x5A, 0x12, [4]byte{0xDE, 0xAD, 0xBE, 0xEF}, SessionAcceptClientPolicy{})); got != 8 {
		t.Fatalf("native payload = %d bytes, want the historical 8", got)
	}

	ConfigureLegacySessionID(true)
	legacy := EncodeSessionAccept(200, 0x5A, 0x12, [4]byte{0xDE, 0xAD, 0xBE, 0xEF}, SessionAcceptClientPolicy{})
	if got := hex.EncodeToString(legacy); got != "c85a12deadbeef" {
		t.Fatalf("legacy payload = %s, want the MasterDNS golden c85a12deadbeef", got)
	}
}

// Pinned against bytes produced by MasterDnsVPN's own encoder (commit bc69a58),
// so a MasterDNS server's policy is read exactly as that server wrote it.
func TestPolicyBlockMatchesMasterDNSEncoder(t *testing.T) {
	const golden = "53c8057810280807d040007836"

	block := EncodeSessionAcceptClientPolicy(samplePolicy())
	if got := hex.EncodeToString(block[:]); got != golden {
		t.Fatalf("policy block differs from MasterDnsVPN's\n got  %s\n want %s", got, golden)
	}
}

// A truncated block must read as "no policy stated" rather than as a set of
// zero ceilings, which a client would clamp itself to death against.
func TestTruncatedPolicyIsTreatedAsAbsent(t *testing.T) {
	defer ConfigureLegacySessionID(false)
	ConfigureLegacySessionID(false)

	payload := EncodeSessionAccept(42, 0x11, 0x22, [4]byte{1, 2, 3, 4}, samplePolicy())
	for cut := 1; cut <= SessionAcceptPolicyPayloadSize; cut++ {
		if _, ok := DecodeSessionAcceptPolicy(payload[:len(payload)-cut]); ok {
			t.Fatalf("a payload short by %d bytes reported a usable policy", cut)
		}
	}
}
