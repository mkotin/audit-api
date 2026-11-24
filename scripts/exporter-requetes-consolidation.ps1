<#
.SYNOPSIS
    Exporte les requêtes de consolidation de dossiers, pour l'évaluation hors ligne d'audit-core.

.DESCRIPTION
    Pour chaque dossier (référence externe ou identifiant), écrit dans le dossier de sortie la
    requête de consolidation telle qu'audit-api l'enverrait à audit-core (mêmes octets), lue par
    GET /api/admin/dossiers/{id}/consolidation/requete : audit-api doit tourner avec le profil dev.
    Le fichier est nommé d'après la référence du dossier (<reference>.json).

    L'évaluation d'audit-core rattache chaque requête à son dossier du jeu de test par sa référence
    (« dossier_004 » doit y figurer, comme dans ESSAI-dossier_004-… créé par le script d'essai
    d'audit-infra) et chaque pièce à son document par son nom de fichier (dossier_004_doc03.pdf) :
    .\scripts\dev.ps1 eval-consolidation --requetes benchmarks/consolidation/requetes
    (dans audit-core). Le dossier de sortie par défaut est celui-là ; il n'est pas versionné, car
    ces requêtes contiennent les valeurs extraites des documents.

.EXAMPLE
    .\scripts\exporter-requetes-consolidation.ps1 -References ESSAI-dossier_004-20261124-101500, ESSAI-dossier_008-20261124-101500
    .\scripts\exporter-requetes-consolidation.ps1 -Ids 6f1c… -Api http://localhost:8080
#>
param(
    [string[]]$References = @(),
    [string[]]$Ids = @(),
    [string]$Api = "http://localhost:8080",
    [string]$Sortie = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) "audit-core\benchmarks\consolidation\requetes")
)

$ErrorActionPreference = "Stop"
if ($References.Count -eq 0 -and $Ids.Count -eq 0) {
    throw "Indiquer au moins un dossier : -References <référence externe> ou -Ids <identifiant>"
}
New-Item -ItemType Directory -Force -Path $Sortie | Out-Null

$dossiers = @()
foreach ($reference in $References) {
    $dossier = Invoke-RestMethod "$Api/api/dossiers?reference=$([uri]::EscapeDataString($reference))"
    $dossiers += [pscustomobject]@{ Id = $dossier.id; Reference = $dossier.referenceExterne }
}
foreach ($id in $Ids) {
    $dossier = Invoke-RestMethod "$Api/api/dossiers/$id"
    $dossiers += [pscustomobject]@{ Id = $dossier.id; Reference = $dossier.referenceExterne }
}

foreach ($dossier in $dossiers) {
    $nom = ($dossier.Reference -replace '[^\w.-]', '_') + ".json"
    $fichier = Join-Path $Sortie $nom
    try {
        # -OutFile : octets écrits tels quels, sans conversion d'encodage
        Invoke-WebRequest "$Api/api/admin/dossiers/$($dossier.Id)/consolidation/requete" -OutFile $fichier | Out-Null
    } catch {
        throw "Export impossible pour $($dossier.Reference) : audit-api doit tourner avec le profil dev ($($_.Exception.Message))"
    }
    Write-Host "$($dossier.Reference) -> $fichier"
}
