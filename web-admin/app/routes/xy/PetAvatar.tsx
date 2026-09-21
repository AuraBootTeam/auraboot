import React, { useEffect, useState } from 'react';
import { xyList, xyGet, SPECIES_EMOJI, type XyRow } from './eduApi';
import { FengyunMotionArtwork, motionPacketForAsset } from './FengyunMotion';

/**
 * PetAvatar — companion artwork with the PRD 10 / FR-045 fallback chain:
 *   worn-skin asset (species × skin × current stage)
 *   → same species default-skin asset (same stage)
 *   → species emoji avatar (never a broken image, never blank).
 *
 * A future real-asset pass only needs to upload xy_skin_asset rows; this
 * component picks them up without code change.
 */
export function usePetVisual(student: XyRow | null, pet: XyRow | null) {
  const [assetUrl, setAssetUrl] = useState<string | null>(null);
  const [fallbackReason, setFallbackReason] = useState<string>('');

  useEffect(() => {
    let cancelled = false;
    setAssetUrl(null);
    setFallbackReason('');
    async function resolve() {
      if (!student || !pet) return;
      const speciesPid = String(pet.xy_pi_species || '');
      const stage = String(student.xy_stu_stage || 'stage_1');
      const wornSkin = String(pet.xy_pi_worn_skin || '');
      const tryLoad = async (skinPid: string) => {
        const rows = await xyList('xy_skin_asset', [
          { field: 'xy_sa_species', value: speciesPid },
          { field: 'xy_sa_skin', value: skinPid },
          { field: 'xy_sa_stage', value: stage },
        ]);
        return rows[0] ? String(rows[0].xy_sa_asset || '') : '';
      };
      let url = '';
      if (wornSkin) {
        url = await tryLoad(wornSkin);
        if (!url && !cancelled) setFallbackReason('worn-skin asset missing');
      }
      if (!url) {
        const skins = await xyList('xy_pet_skin', [{ field: 'xy_sk_species', value: speciesPid }]);
        const defaultSkin = skins.find((s) => s.xy_sk_is_default === true || s.xy_sk_is_default === 'true');
        if (defaultSkin) {
          url = await tryLoad(String(defaultSkin.pid));
          if (!url && !cancelled) setFallbackReason('default-skin asset missing');
        }
      }
      if (!cancelled) setAssetUrl(url || null);
    }
    resolve();
    return () => {
      cancelled = true;
    };
  }, [student?.pid, pet?.pid, student?.xy_stu_stage, pet?.xy_pi_worn_skin]);

  return { assetUrl, fallbackReason };
}

export function PetAvatar({
  assetUrl,
  speciesCode,
  size = 180,
}: {
  assetUrl: string | null;
  speciesCode: string;
  size?: number;
}) {
  const [broken, setBroken] = useState(false);
  useEffect(() => setBroken(false), [assetUrl]);
  const emoji = SPECIES_EMOJI[speciesCode] || '🌱';
  const showImg = assetUrl && !broken;
  return (
    <div
      className="relative grid place-items-center overflow-hidden rounded-full"
      style={{
        width: size,
        height: size,
        background: 'radial-gradient(circle at 50% 32%, #ffffffcc 0 38%, #ffffff2e 39% 100%)',
      }}
      data-testid="pet-avatar"
      data-asset={assetUrl || 'fallback'}
    >
      {showImg ? (
        motionPacketForAsset(assetUrl) ? (
          <FengyunMotionArtwork
            assetUrl={assetUrl}
            alt="成长伙伴"
            size={size}
            clickClip="greet"
            className="p-2"
          />
        ) : (
          <img
            src={assetUrl}
            alt="成长伙伴"
            className="h-full w-full object-contain p-2"
            onError={() => setBroken(true)}
          />
        )
      ) : (
        <span style={{ fontSize: size * 0.52 }} role="img" aria-label="pet placeholder">
          {emoji}
        </span>
      )}
    </div>
  );
}


