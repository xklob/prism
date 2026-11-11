const token = location.hash.slice(1) || sessionStorage.getItem('prismAdmin') || '';
if (token) sessionStorage.setItem('prismAdmin', token);
history.replaceState(null, '', location.pathname);
const $ = id => document.getElementById(id);
let role = 'viewer', host = '', historySamples = [];
const dirty = new Set();
const numeric = ['bpm','meter','phraseBars','scene','palette','flashEvery','flashMs','invertMs',
  'speed','intensity','complexity','symmetry','distortion','zoom','hue','rotation','colorSpeed','morph'];
for (const [id, name, min, max, step, value] of [
  ['speed','Motion speed',.05,2,.05,.7],['intensity','Intensity',.25,1.6,.05,.95],
  ['complexity','Complexity',0,1,.02,.72],['symmetry','Symmetry',3,20,1,8],
  ['distortion','Distortion',0,1.5,.05,.6],['zoom','Zoom',.45,3,.05,1],
  ['hue','Hue',0,1,.01,0],['rotation','Rotation',-1,1,.02,.12],
  ['colorSpeed','Color speed',0,.2,.005,.04],['morph','Morph',0,1.5,.05,.45]
]) {
  const label = document.createElement('label'); label.textContent = name;
  const input = document.createElement('input'); Object.assign(input,{id,type:'range',min,max,step,value});
  label.append(input); $('sliders').append(label);
}
for (const key of [...numeric, 'mode']) {
  $(key).addEventListener('input', () => dirty.add(key));
  $(key).addEventListener('change', () => dirty.add(key));
}
async function api(path, body) {
  const response = await fetch('/api/' + path, { method: body ? 'POST' : 'GET',
    headers: { Authorization: 'Bearer ' + token, ...(body ? {'Content-Type':'application/json'} : {}) },
    ...(body ? {body: JSON.stringify(body)} : {}) });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || 'Controller unavailable');
  return result;
}
async function command(body) {
  try { await api('command',body); $('error').textContent = ''; return true; }
  catch (error) { $('error').textContent = error.message; return false; }
}
async function invitation() {
  try {
    const data = await api('join?role=' + role);
    $('qr').src = data.qr; $('invite').value = data.link;
    $('roleHelp').textContent = role === 'source'
      ? 'For the VJ only: join, then connect microphone or system audio in Prism.'
      : 'Scan with your phone camera and open Prism. Or paste the invitation into Crowd in Prism.';
    $('viewers').classList.toggle('primary',role === 'viewer');
    $('audioRole').classList.toggle('primary',role === 'source');
  } catch (error) { $('error').textContent = error.message; }
}
$('viewers').onclick = () => { role = 'viewer'; invitation(); };
$('audioRole').onclick = () => { role = 'source'; invitation(); };
for (const action of ['start','blackout','tap','align']) $(action).onclick = async () => {
  if (await command({action}) && action === 'tap') dirty.delete('bpm');
};
$('source').onchange = () => command({action:'source',source:$('source').value});
$('apply').onclick = async () => {
  const fields = numeric.filter(key => $('source').value !== 'audio' || !['bpm','meter','phraseBars'].includes(key));
  if (await command({action:'settings',mode:$('mode').value,
    ...Object.fromEntries(fields.map(key => [key, key === 'flashEvery' && $('flashEvery').value === '4'
      ? Number($('meter').value) : Number($(key).value)]))})) dirty.clear();
};
$('interface').onchange = async () => { await command({action:'interface',host:$('interface').value}); host=''; };
$('copy').onclick = async () => {
  try { await navigator.clipboard.writeText($('invite').value); $('error').textContent='Invitation copied.'; }
  catch { $('invite').select(); $('error').textContent='Copy the selected invitation.'; }
};
$('export').onclick = () => {
  const blob = new Blob([JSON.stringify({kind:'Prism software timing diagnostics',samples:historySamples},null,2)],{type:'application/json'});
  const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='prism-timing.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
};
async function refresh() {
  try {
    const data = await api('status');
    historySamples.push({at:Date.now(),showTime:data.time,clients:data.clients});
    if(historySamples.length>7200)historySamples.shift();
    if (host !== data.host) {
      host = data.host; $('interface').replaceChildren();
      for (const item of data.interfaces) {
        const option = document.createElement('option'); option.value=item.address;option.textContent=item.name+' · '+item.address;
        option.selected=item.address===host;$('interface').append(option);
      }
      invitation();
    }
    const cue = data.cues.filter(c => c.effective <= data.time).at(-1) || data.cues[0];
    const editingCue = data.cues.at(-1);
    for(const key of numeric) if(!dirty.has(key)) $(key).value=key==='flashEvery'&&editingCue[key]>=3?'4':editingCue[key];
    if(!dirty.has('mode')) $('mode').value=editingCue.mode;
    for(const key of ['bpm','meter','phraseBars']) $(key).disabled=data.music.source==='audio';
    const position = cue.beat+(data.time-cue.anchor)*cue.bpm/60000;
    $('tempo').textContent=cue.bpm.toFixed(1).replace(/\.0$/,'');
    $('playing').textContent=data.blackout?'BLACKOUT':cue.running?'PLAYING':'QUEUED';
    $('position').textContent=cue.running&&!data.blackout?'Beat '+(Math.floor(((position%cue.meter)+cue.meter)%cue.meter)+1)+' · Bar '+(Math.floor(position/cue.meter)+1):'Waiting to start';
    $('source').value=data.music.source;
    $('music').textContent=data.music.source==='manual'?'Manual beat, bar, and phrase alignment':
      'Audio '+(data.music.ready?'tracking':'waiting')+' · Beat '+Math.round(data.music.confidence*100)+'% · Bar '+Math.round(data.music.barConfidence*100)+'% · Phrase '+Math.round(data.music.phraseConfidence*100)+'%';
    $('queued').textContent=data.cues.length>1?'Next cue in '+Math.max(0,(data.cues[1].effective-data.time)/1000).toFixed(1)+' s':'';
    $('capacity').textContent=data.maxClients
      ? 'This hotspot supports '+data.maxClients+' connected devices, including the audio input phone.'
      : 'Join the laptop hotspot, then scan. The hotspot determines how many phones can connect.';
    $('phones').replaceChildren();
    for(const phone of data.clients) {
      const row=document.createElement('tr');
      const cells=[phone.name+(phone.source?' · input':''),phone.state||'Syncing',
        typeof phone.clockMs==='number'?'±'+phone.clockMs.toFixed(1)+' ms':'Waiting',
        typeof phone.fps==='number'?phone.fps.toFixed(0):'Waiting',String(phone.missed??0)];
      cells.forEach((value,i)=>{const cell=document.createElement('td');cell.textContent=value;if(i===1)cell.className=String(phone.state||'').toLowerCase();row.append(cell);});
      $('phones').append(row);
    }
    const ready=data.clients.filter(c=>c.state==='Ready').length;
    $('summary').textContent=data.clients.length+' connected · '+ready+' ready'+(data.socketError?' · '+data.socketError:'');
  } catch (error) { $('error').textContent=error.message; }
  setTimeout(refresh,500);
}
refresh();
