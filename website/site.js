document.getElementById("year")?.append(new Date().getFullYear());
const toggle=document.getElementById("navToggle");
const menu=document.getElementById("mobileNav");
if(toggle&&menu){toggle.addEventListener("click",()=>{const opened=menu.hidden;menu.hidden=!opened;toggle.setAttribute("aria-expanded",String(opened));});menu.querySelectorAll("a").forEach(a=>a.addEventListener("click",()=>{menu.hidden=true;toggle.setAttribute("aria-expanded","false");}));}
const search=document.getElementById("transactionSearch");
const rows=[...document.querySelectorAll("#transactions tr")];
search?.addEventListener("input",()=>{const term=search.value.trim().toLowerCase();rows.forEach(tr=>{tr.hidden=!tr.textContent.toLowerCase().includes(term);});});
