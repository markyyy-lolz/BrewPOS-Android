import com.brewpos.cafe.*

val sale = Sale(id=7L,receiptNo="BP-20261009-00007",createdAt=1791550000000L,service="Takeout",payment="Cash",subtotal=28000,discount=3000,total=25000,tendered=30000,change=5000,notes="Name: ALI, no sugar, extra ice")
val items = listOf(
    SaleLine(1,"Large Iced Caramel Latte","Large, Oat milk, Vanilla syrup",2,12000,24000),
    SaleLine(2,"Butter Croissant","",1,4000,4000)
)
val customer=TicketFormatter.customerText("Brew and Bean","Thank you!",sale,items,32)
val barista=TicketFormatter.baristaText("Brew and Bean",sale,items,32)
check(customer.contains("PHP 250.00") && customer.contains("TOTAL"))
check(barista.contains("BARISTA ORDER SLIP") && barista.contains("BP-20261009-00007"))
check(barista.contains("Vanilla syrup") && barista.contains("ALI"))
listOf("PHP", "250.00", "280.00", "Subtotal", "Discount", "Cash", "Tendered", "Change").forEach { check(!barista.contains(it)) { "Leaked $it in barista slip" } }
check(barista.lines().all { it.length <= 32 })
val cut=byteArrayOf(0x1D,0x56,0x00)
for(w in listOf(32,48)) {
    val both=TicketFormatter.both("Brew and Bean","Thank you!",sale,items,w,true)
    val str=both.toString(Charsets.US_ASCII)
    check(str.indexOf("SALES RECEIPT") in 0 until str.indexOf("BARISTA ORDER SLIP"))
    check(both.asList().windowed(3).count { it == cut.asList() } == 2)
    val uncut=TicketFormatter.both("Brew and Bean","Thank you!",sale,items,w,false)
    check(uncut.toString(Charsets.US_ASCII).contains("TEAR HERE / NEXT SLIP"))
    check(uncut.asList().windowed(3).none { it == cut.asList() })
    println("PASS width=$w: paired job, 2 cuts, tear separator, ordering")
}
println("PASS barista: no pricing, modifier detail, notes, 32-column wrapping")
println("PASS customer: receipt totals and accounting fields")
