const ROLES=Object.freeze(['issuer-installer','issuer-probe','postgres','mailpit','demo-site','callback-receiver']);
const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
const text=value=>typeof value==='string'&&value.length>0;

/** Validate metadata identity and return unverified inventory, never closure or admission proof. No I/O. */
export function priorRuntimeInventory(metadata,expected) {
  try {
    if(!object(metadata)||!object(expected))throw null;
    const {runId,expectedSource,daemon,expectedMetadataStatus,backend}=expected;
    if(!text(runId)||!text(expectedSource)||!text(daemon)||typeof backend!=='boolean'
      ||metadata.runId!==runId||metadata.expectedSource!==expectedSource||metadata.daemon!==daemon
      ||metadata.project!=='stripe-'+runId||!['PENDING','STOPPED'].includes(expectedMetadataStatus)
      ||metadata.status!==expectedMetadataStatus)throw null;
    const roles=[...ROLES,...(backend?['backend']:[])].sort(),containers=metadata.containers;
    if(!object(containers)||Object.keys(containers).length!==roles.length
      ||roles.some(role=>!Object.hasOwn(containers,role)))throw null;
    return Object.freeze(roles.map(role=>{
      const container=containers[role],id=container?.id;
      if(!object(container)||typeof id!=='string'||id.length!==64||!/^[a-f0-9]{64}$/.test(id))throw null;
      return Object.freeze({id,run:runId,role});
    }));
  }catch{throw new Error('PRIOR_METADATA_UNCONFIRMED');}
}
