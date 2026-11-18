package main

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
)

type contextKey string

const userContextKey contextKey = "paceapp-user"

type authConfig struct {
	Username       string
	DisplayName    string
	Password       string
	PasswordSHA256 string
	SigningSecret  []byte
	TokenTTL       time.Duration
	HermesAPIKey   string
}

type tokenClaims struct {
	Sub string `json:"sub"`
	Exp int64  `json:"exp"`
	JTI string `json:"jti"`
}

func (a authConfig) validate() error {
	if strings.TrimSpace(a.Username) == "" {
		return errors.New("AUTH_USERNAME is required")
	}
	if a.Password == "" && strings.TrimSpace(a.PasswordSHA256) == "" {
		return errors.New("AUTH_PASSWORD or AUTH_PASSWORD_SHA256 is required")
	}
	if len(a.SigningSecret) < 16 {
		return errors.New("AUTH_SIGNING_SECRET must be at least 16 bytes")
	}
	return nil
}

func (a authConfig) displayName() string {
	if strings.TrimSpace(a.DisplayName) != "" {
		return strings.TrimSpace(a.DisplayName)
	}
	return a.Username
}

func (a authConfig) checkCredentials(username, password string) bool {
	expectedUser := sha256.Sum256([]byte(strings.TrimSpace(a.Username)))
	actualUser := sha256.Sum256([]byte(strings.TrimSpace(username)))
	userOK := subtle.ConstantTimeCompare(expectedUser[:], actualUser[:]) == 1

	var passOK bool
	if shaHex := strings.TrimSpace(a.PasswordSHA256); shaHex != "" {
		expectedHash, err := hex.DecodeString(strings.ToLower(shaHex))
		if err == nil && len(expectedHash) == sha256.Size {
			actualHash := sha256.Sum256([]byte(password))
			passOK = subtle.ConstantTimeCompare(expectedHash, actualHash[:]) == 1
		}
	} else if a.Password != "" {
		expectedHash := sha256.Sum256([]byte(a.Password))
		actualHash := sha256.Sum256([]byte(password))
		passOK = subtle.ConstantTimeCompare(expectedHash[:], actualHash[:]) == 1
	}

	return userOK && passOK
}

func (a authConfig) checkHermesKey(provided string) bool {
	if strings.TrimSpace(a.HermesAPIKey) == "" || strings.TrimSpace(provided) == "" {
		return false
	}
	expected := sha256.Sum256([]byte(strings.TrimSpace(a.HermesAPIKey)))
	actual := sha256.Sum256([]byte(strings.TrimSpace(provided)))
	return subtle.ConstantTimeCompare(expected[:], actual[:]) == 1
}

func (a authConfig) issueToken(now time.Time) (string, time.Time, error) {
	jtiBytes := make([]byte, 12)
	if _, err := rand.Read(jtiBytes); err != nil {
		return "", time.Time{}, err
	}
	ttl := a.TokenTTL
	if ttl <= 0 {
		ttl = 90 * 24 * time.Hour
	}
	exp := now.Add(ttl)
	claims := tokenClaims{
		Sub: strings.TrimSpace(a.Username),
		Exp: exp.Unix(),
		JTI: hex.EncodeToString(jtiBytes),
	}
	payloadBytes, err := json.Marshal(claims)
	if err != nil {
		return "", time.Time{}, err
	}
	payloadEnc := base64.RawURLEncoding.EncodeToString(payloadBytes)
	sig := a.sign(payloadEnc)
	return "v1." + payloadEnc + "." + sig, exp, nil
}

func (a authConfig) verifyToken(token string, now time.Time) (tokenClaims, bool) {
	parts := strings.Split(strings.TrimSpace(token), ".")
	if len(parts) != 3 || parts[0] != "v1" {
		return tokenClaims{}, false
	}
	expectedSig := a.sign(parts[1])
	if !hmac.Equal([]byte(parts[2]), []byte(expectedSig)) {
		return tokenClaims{}, false
	}
	rawPayload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return tokenClaims{}, false
	}
	var claims tokenClaims
	if err := json.Unmarshal(rawPayload, &claims); err != nil {
		return tokenClaims{}, false
	}
	if claims.Sub != strings.TrimSpace(a.Username) || claims.Exp <= now.Unix() {
		return tokenClaims{}, false
	}
	return claims, true
}

func (a authConfig) sign(encodedPayload string) string {
	mac := hmac.New(sha256.New, a.SigningSecret)
	mac.Write([]byte(encodedPayload))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

func (s *server) authMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if hermesKey := r.Header.Get("X-Hermes-Key"); hermesKey != "" && s.auth.checkHermesKey(hermesKey) {
			ctx := context.WithValue(r.Context(), userContextKey, "hermes")
			next.ServeHTTP(w, r.WithContext(ctx))
			return
		}
		raw := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		if raw == "" {
			errorJSON(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		claims, ok := s.auth.verifyToken(raw, time.Now())
		if !ok {
			errorJSON(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		ctx := context.WithValue(r.Context(), userContextKey, claims.Sub)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}
