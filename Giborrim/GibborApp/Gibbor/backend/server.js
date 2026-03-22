/**
 * GIBBOR Backend — Server-side Crossmint operations
 *
 * Este servidor es el intermediario entre Android y Crossmint.
 * Usa la server-side API key (sk_*) que NO puede estar en Android.
 *
 * Endpoints:
 *   POST /api/incident   — crea wallet (si no existe) + transacción on-chain
 *   GET  /api/incident/:txId — polling del estado de una transacción
 *   POST /api/evidence    — (FASE 2) recibe hash de evidencia y lo ancla on-chain
 */

require("dotenv").config();
const express = require("express");
const cors = require("cors");

const app = express();
app.use(cors());
app.use(express.json());

// ─── Config ──────────────────────────────────────────────────────────────────

const {
  CROSSMINT_SERVER_API_KEY,
  CROSSMINT_BASE_URL,
  STELLAR_CONTRACT_ID,
  PORT = 3001,
} = process.env;

if (!CROSSMINT_SERVER_API_KEY || CROSSMINT_SERVER_API_KEY === "sk_staging_REPLACE_ME") {
  console.error("⚠️  CROSSMINT_SERVER_API_KEY no configurada. Edita backend/.env");
  process.exit(1);
}

console.log("🔑 Server API key cargada:", CROSSMINT_SERVER_API_KEY.slice(0, 20) + "...");
console.log("🌐 Crossmint base URL:", CROSSMINT_BASE_URL);
console.log("📄 Contract ID:", STELLAR_CONTRACT_ID);

// ─── Helper: HTTP request a Crossmint ────────────────────────────────────────

async function crossmintRequest(method, path, body = null) {
  const url = `${CROSSMINT_BASE_URL}${path}`;
  const options = {
    method,
    headers: {
      "Content-Type": "application/json",
      "X-API-KEY": CROSSMINT_SERVER_API_KEY,
    },
  };

  if (body) {
    options.body = JSON.stringify(body);
  }

  console.log(`→ ${method} ${url}`);
  if (body) console.log("  Body:", JSON.stringify(body, null, 2));

  const res = await fetch(url, options);
  const text = await res.text();

  let json;
  try {
    json = JSON.parse(text);
  } catch {
    json = { raw: text };
  }

  console.log(`← ${res.status}`, JSON.stringify(json, null, 2));

  if (!res.ok) {
    const err = new Error(`Crossmint HTTP ${res.status}`);
    err.status = res.status;
    err.body = json;
    throw err;
  }

  return json;
}

// ─── POST /api/incident ──────────────────────────────────────────────────────

app.post("/api/incident", async (req, res) => {
  try {
    const { email, incidentId, timestamp, latE7, lonE7, initialHash } = req.body;

    // Validación básica
    if (!email || !incidentId || timestamp == null || latE7 == null || lonE7 == null || !initialHash) {
      return res.status(400).json({
        success: false,
        error: "Campos requeridos: email, incidentId, timestamp, latE7, lonE7, initialHash",
      });
    }

    console.log(`\n══════════════════════════════════════`);
    console.log(`📡 Nuevo incidente: ${incidentId}`);
    console.log(`   email: ${email}`);
    console.log(`   lat_e7: ${latE7}, lon_e7: ${lonE7}`);
    console.log(`   hash: ${initialHash}`);
    console.log(`══════════════════════════════════════\n`);

    // 1) Asegurar que exista la wallet Stellar del usuario
    console.log("1️⃣  Creando/verificando wallet Stellar...");
    let wallet;
    try {
      wallet = await crossmintRequest("POST", "/wallets", {
        chainType: "stellar",
        type: "smart",
        owner: `email:${email}`,
      });
    } catch (err) {
      // 409 = wallet ya existe, eso está bien
      if (err.status === 409) {
        console.log("   Wallet ya existe (409) — OK");
        wallet = err.body;
      } else {
        throw err;
      }
    }

    const walletAddress = wallet?.address || wallet?.publicKey || "unknown";
    console.log(`   Wallet address: ${walletAddress}`);

    // 2) Construir el locator del wallet
    const walletLocator = encodeURIComponent(`email:${email}:stellar:smart`);

    // 3) Crear la transacción contract-call
    console.log("2️⃣  Enviando create_incident on-chain...");
    const txBody = {
      params: {
        transaction: {
          type: "contract-call",
          contractId: STELLAR_CONTRACT_ID,
          method: "create_incident",
          args: {
            incident_id: incidentId,
            timestamp: timestamp,
            lat_e7: latE7,
            lon_e7: lonE7,
            initial_hash: initialHash,
          },
        },
        signer: "api-key",
      },
    };

    const txResult = await crossmintRequest(
      "POST",
      `/wallets/${walletLocator}/transactions`,
      txBody
    );

    // 4) Extraer datos relevantes de la respuesta
    const txId = txResult.id || "";
    const status = txResult.status || "unknown";
    const onChain = txResult.onChain || {};
    const txHash = onChain.hash || onChain.txId || txId;
    const explorerLink = onChain.explorerLink || "";

    console.log(`✅ TX creada: status=${status}, id=${txId}`);

    return res.json({
      success: true,
      status,
      txId,
      txHash,
      explorerLink,
      walletAddress,
      raw: txResult,
    });
  } catch (err) {
    console.error("❌ Error en /api/incident:", err.message);
    const status = err.status || 500;
    return res.status(status).json({
      success: false,
      error: err.message,
      details: err.body || null,
    });
  }
});

// ─── GET /api/incident/:txId ─────────────────────────────────────────────────

app.get("/api/incident/:txId", async (req, res) => {
  try {
    const { txId } = req.params;
    const { email } = req.query;

    if (!email || !txId) {
      return res.status(400).json({
        success: false,
        error: "Parámetros requeridos: txId (path), email (query)",
      });
    }

    const walletLocator = encodeURIComponent(`email:${email}:stellar:smart`);
    const txIdEncoded = encodeURIComponent(txId);

    const txResult = await crossmintRequest(
      "GET",
      `/wallets/${walletLocator}/transactions/${txIdEncoded}`
    );

    const status = txResult.status || "unknown";
    const onChain = txResult.onChain || {};
    const txHash = onChain.hash || onChain.txId || txId;
    const explorerLink = onChain.explorerLink || "";

    return res.json({
      success: true,
      status,
      txId,
      txHash,
      explorerLink,
      raw: txResult,
    });
  } catch (err) {
    console.error("❌ Error en GET /api/incident:", err.message);
    const status = err.status || 500;
    return res.status(status).json({
      success: false,
      error: err.message,
      details: err.body || null,
    });
  }
});

// ─── POST /api/evidence (FASE 2 — placeholder) ──────────────────────────────

app.post("/api/evidence", async (req, res) => {
  // TODO FASE 2: recibir hash de evidencia + incident_id, anclar en blockchain
  return res.status(501).json({
    success: false,
    error: "Endpoint aún no implementado (FASE 2)",
  });
});

// ─── Health check ────────────────────────────────────────────────────────────

app.get("/health", (req, res) => {
  res.json({ status: "ok", contractId: STELLAR_CONTRACT_ID });
});

// ─── Start ───────────────────────────────────────────────────────────────────

app.listen(PORT, "0.0.0.0", () => {
  console.log(`\n🚀 GIBBOR Backend corriendo en http://0.0.0.0:${PORT}`);
  console.log(`   POST /api/incident     — crear incidente on-chain`);
  console.log(`   GET  /api/incident/:id — polling estado de TX`);
  console.log(`   POST /api/evidence     — (FASE 2) anclar hash evidencia`);
  console.log(`   GET  /health           — health check\n`);
});
