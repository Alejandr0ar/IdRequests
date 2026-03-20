description = "mejora el panel Requester añadiendo navegación interactiva del historial de peticiones y respuestas HTTP."

zapAddOn {
    addOnName.set("IdRequests")

    manifest {
        author.set("alejandroar")
    }
}

crowdin {
    configuration {
        val resourcesPath = "org/zaproxy/addon/${zapAddOn.addOnId.get()}/resources/"
        tokens.put("%messagesPath%", resourcesPath)
        tokens.put("%helpPath%", resourcesPath)
    }
}
