#![no_std]

use soroban_sdk::{
    contract, contracterror, contractimpl, contracttype,
    symbol_short, Env, String, Symbol, Vec,
};

const STATUS_TRIGGERED: u32 = 0;
const STATUS_MEDIA_ATTACHED: u32 = 1;

#[derive(Clone)]
#[contracttype]
pub struct Incident {
    pub incident_id: String,
    pub timestamp: i64,
    pub lat_e7: i32,
    pub lon_e7: i32,
    pub initial_hash: String,
    pub status: u32,
}

#[derive(Clone)]
#[contracttype]
pub struct MediaHash {
    pub media_type: String,
    pub media_hash: String,
    pub uploaded_at: i64,
}

#[derive(Clone)]
#[contracttype]
pub enum DataKey {
    Incident(String),
    Media(String),
}

#[contracterror]
#[derive(Copy, Clone, Debug, Eq, PartialEq, PartialOrd, Ord)]
#[repr(u32)]
pub enum Error {
    IncidentAlreadyExists = 1,
    IncidentNotFound = 2,
}

/// Tópicos del evento create_incident: (status, incident_id)
/// GoldSky indexa los campos data como: timestamp, lat_e7, lon_e7, initial_hash
pub struct IncidentCreatedTopics(pub Symbol, pub String);

/// Tópicos del evento attach_media_hash: (status, incident_id)
pub struct MediaAttachedTopics(pub Symbol, pub String);

#[contract]
pub struct IncidentRegistryContract;

#[contractimpl]
impl IncidentRegistryContract {
    pub fn create_incident(
        env: Env,
        incident_id: String,
        timestamp: i64,
        lat_e7: i32,
        lon_e7: i32,
        initial_hash: String,
    ) -> Result<(), Error> {
        let incident_key = DataKey::Incident(incident_id.clone());

        if env.storage().persistent().has(&incident_key) {
            return Err(Error::IncidentAlreadyExists);
        }

        let incident = Incident {
            incident_id: incident_id.clone(),
            timestamp,
            lat_e7,
            lon_e7,
            initial_hash: initial_hash.clone(),
            status: STATUS_TRIGGERED,
        };

        env.storage().persistent().set(&incident_key, &incident);

        let empty_media = Vec::<MediaHash>::new(&env);
        env.storage()
            .persistent()
            .set(&DataKey::Media(incident_id.clone()), &empty_media);

        env.events().publish(
            (symbol_short!("created"), incident_id),
            (timestamp, lat_e7, lon_e7, initial_hash),
        );

        Ok(())
    }

    pub fn attach_media_hash(
        env: Env,
        incident_id: String,
        media_type: String,
        media_hash: String,
        uploaded_at: i64,
    ) -> Result<(), Error> {
        let incident_key = DataKey::Incident(incident_id.clone());

        let mut incident: Incident = env
            .storage()
            .persistent()
            .get(&incident_key)
            .ok_or(Error::IncidentNotFound)?;

        let media_key = DataKey::Media(incident_id.clone());
        let mut proofs: Vec<MediaHash> = env
            .storage()
            .persistent()
            .get(&media_key)
            .unwrap_or(Vec::new(&env));

        proofs.push_back(MediaHash {
            media_type: media_type.clone(),
            media_hash: media_hash.clone(),
            uploaded_at,
        });
        env.storage().persistent().set(&media_key, &proofs);

        incident.status = STATUS_MEDIA_ATTACHED;
        env.storage().persistent().set(&incident_key, &incident);

        env.events().publish(
            (symbol_short!("media"), incident_id),
            (media_type, media_hash, uploaded_at),
        );

        Ok(())
    }

    pub fn get_incident(env: Env, incident_id: String) -> Result<Incident, Error> {
        env.storage()
            .persistent()
            .get(&DataKey::Incident(incident_id))
            .ok_or(Error::IncidentNotFound)
    }

    pub fn get_media_hashes(env: Env, incident_id: String) -> Result<Vec<MediaHash>, Error> {
        let incident_exists = env
            .storage()
            .persistent()
            .has(&DataKey::Incident(incident_id.clone()));

        if !incident_exists {
            return Err(Error::IncidentNotFound);
        }

        Ok(env
            .storage()
            .persistent()
            .get(&DataKey::Media(incident_id))
            .unwrap_or(Vec::new(&env)))
    }
}

mod test;
