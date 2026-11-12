import nodemailer from 'nodemailer';

const escapeHtml = value => String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function invitationMailContent({inviteUrl,kind='invitation',driverName='운전자',passengerName='친구',destinationLabel=null}) {
  const link=new URL(inviteUrl);
  if(link.protocol!=='https:' || link.username || link.password)throw new Error('Invitation email requires an HTTPS link');
  const arrival=kind==='arrival';
  const destination=destinationLabel || '아직 미정이에요';
  const heading=arrival?'픽업 차량이 곧 도착해요':`${driverName}님이 픽업에 초대했어요`;
  const intro=arrival?`${passengerName}님, ${driverName}님의 차량이 탑승 장소까지 약 5분 이내로 예상돼요.`:`${passengerName}님, ${driverName}님이 함께 이동하려고 픽미 초대를 보냈어요.`;
  const action=arrival?'차량 도착 정보 확인하기':'탑승 장소 선택하고 수락하기';
  const explanation=arrival?'탑승 장소에서 준비해 주세요. 실제 교통 상황에 따라 도착 시간은 달라질 수 있어요.':'링크를 열어 만날 장소를 고르고 초대를 수락해 주세요. 도착 후 준비 완료 버튼으로 친구에게 알려줄 수 있어요.';
  const footer='운전자 이름과 목적지는 초대에 입력된 정보예요. 아는 친구가 보낸 초대인지 확인해 주세요. 픽미 초대 페이지에서는 비밀번호나 결제를 요구하지 않아요. 초대는 생성 후 24시간 동안 유효해요.';
  const e=escapeHtml;
  return {
    subject:arrival?`[픽미] ${driverName}님 도착까지 약 5분 이내예요`:`[픽미] ${driverName}님이 보낸 픽업 초대`,
    text:`${heading}\n\n${intro}\n\n태우러 오는 사람: ${driverName}\n초대받는 사람: ${passengerName}\n함께 갈 목적지: ${destination}\n\n${explanation}\n\n${action}\n${inviteUrl}\n\n${footer}`,
    html:`<!doctype html><html lang="ko"><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body style="margin:0;padding:24px;background:#f3f6fa;font-family:Arial,sans-serif;color:#17243b"><main style="max-width:560px;margin:auto;background:white;border-radius:16px;padding:32px"><p style="font-weight:bold;color:#2366d1;letter-spacing:2px">PICKME · 픽미</p><h1 style="font-size:25px;line-height:1.4">${e(heading)}</h1><p style="line-height:1.8">${e(intro)}</p><div style="background:#f1f5fc;border-radius:12px;padding:20px;line-height:1.9">태우러 오는 사람: <strong>${e(driverName)}</strong><br>초대받는 사람: <strong>${e(passengerName)}</strong><br>함께 갈 목적지: <strong>${e(destination)}</strong></div><p style="line-height:1.8">${e(explanation)}</p><table role="presentation" border="0" cellpadding="0" cellspacing="0" style="margin:28px 0;width:100%"><tr><td align="center" bgcolor="#2366d1" style="border-radius:10px"><a href="${e(inviteUrl)}" target="_blank" rel="noopener noreferrer" style="display:block;padding:18px 22px;border:1px solid #2366d1;border-radius:10px;color:#ffffff!important;font-size:17px;line-height:1.5;text-align:center;text-decoration:none;font-weight:bold">${e(action)}</a></td></tr></table><p style="font-size:13px;color:#58677d;line-height:1.8">${e(footer)}</p><p style="font-size:12px;color:#58677d;word-break:break-all">버튼 대신 아래 링크를 눌러도 같은 초대가 열려요.<br><a href="${e(inviteUrl)}" target="_blank" rel="noopener noreferrer" style="color:#2366d1;text-decoration:underline;word-break:break-all">${e(inviteUrl)}</a></p></main></body></html>`,
  };
}

export function createGmailSender(env, transportFactory = nodemailer.createTransport) {
  const user = env.PICKME_GMAIL_USER?.trim();
  const pass = env.PICKME_GMAIL_APP_PASSWORD?.replace(/\s/g, '');
  if (!user || !pass) return null;
  const transport = transportFactory({host:'smtp.gmail.com',port:465,secure:true,auth:{user,pass},connectionTimeout:2000,greetingTimeout:2000,socketTimeout:3000,logger:false,debug:false});
  return async ({to,...context}) => {
    const info=await transport.sendMail({from:{name:'픽미',address:user},to,...invitationMailContent(context),disableFileAccess:true,disableUrlAccess:true});
    if(!info.accepted?.length)throw new Error('Mail not accepted');
  };
}
